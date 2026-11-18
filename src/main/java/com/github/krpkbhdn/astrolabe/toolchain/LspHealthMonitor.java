package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel;
import com.github.krpkbhdn.astrolabe.workspace.ScheduledSweep;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Probes the language servers detected in watched project folders and reports changes; a FAILED one is retried,
 * and when this app downloaded it, reinstalled at most once per cooldown. Never touches tools the user installed elsewhere.
 */
@Component
@EnableConfigurationProperties(LspHealthProperties.class)
public class LspHealthMonitor extends ScheduledSweep {

    private static final Logger log = LoggerFactory.getLogger(LspHealthMonitor.class);
    private static final String JDTLS = "jdtls";
    private static final String LSP_HEALTH_THREAD = "lsp-health";

    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    @FunctionalInterface
    interface Later {
        void run(Runnable task, Duration delay);
    }

    private static final class Watcher {
        private final Path directory;
        private final Consumer<List<DetectedTechnology>> onChange;
        private List<DetectedTechnology> last;
        private volatile boolean active = true;

        Watcher(Path directory, Consumer<List<DetectedTechnology>> onChange) {
            this.directory = directory;
            this.onChange = onChange;
        }

        void cancel() {
            active = false;
        }

        synchronized void deliver(List<DetectedTechnology> current) {
            if (active && !current.equals(last)) {
                last = current;
                try {
                    onChange.accept(current);
                } catch (RuntimeException e) {
                    log.warn("A language server watcher failed: {}", e.getClass().getSimpleName());
                }
            }
        }
    }

    private final ProjectToolsService projectTools;
    private final ToolProbe probe;
    private final ToolInstallService installer;
    private final LspHealthProperties properties;
    private final Clock clock;
    private final Pause pause;
    private final List<Watcher> watchers = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> lastReinstall = new ConcurrentHashMap<>();
    private final Set<String> notRepairedByReinstall = ConcurrentHashMap.newKeySet();
    private final Map<String, String> details = new ConcurrentHashMap<>();
    private final Set<Path> awaitingImport = ConcurrentHashMap.newKeySet();
    private final Map<Path, Instant> pendingSince = new ConcurrentHashMap<>();
    private final ManagedToolsDirectory managedTools;
    private final Later later;

    @Autowired
    public LspHealthMonitor(ProjectToolsService projectTools, ToolProbe probe, ToolInstallService installer,
                            LspHealthProperties properties, ManagedToolsDirectory managedTools) {
        this(projectTools, probe, installer, properties, managedTools, Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()),
                (task, delay) -> CompletableFuture.runAsync(task,
                        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)));
    }

    LspHealthMonitor(ProjectToolsService projectTools, ToolProbe probe, ToolInstallService installer,
                     LspHealthProperties properties, ManagedToolsDirectory managedTools, Clock clock, Pause pause, Later later) {
        super("lsp-health", properties.interval());
        this.later = later;
        this.managedTools = managedTools;
        this.projectTools = projectTools;
        this.probe = probe;
        this.installer = installer;
        this.properties = properties;
        this.clock = clock;
        this.pause = pause;
    }

    /** @return unsubscribe handle; the first state arrives with the next check, or call {@link #checkNow}. */
    public Runnable watch(Path projectDir, Consumer<List<DetectedTechnology>> onChange) {
        pendingSince.remove(projectDir);
        var watcher = new Watcher(projectDir, onChange);
        watchers.add(watcher);
        return () -> {
            watcher.cancel();
            watchers.remove(watcher);
        };
    }

    /** Probes on the calling thread without recovery, so the first state does not wait for an interval. */
    public void checkNow(Path projectDir) {
        check(projectDir, false);
    }

    @Override
    public void sweep() {
        try {
            inParallel(watchers.stream().map(watcher -> watcher.directory).distinct().toList(), directory -> {
                check(directory, true);
                return null;
            });
        } catch (RuntimeException | Error e) {
            log.warn("Language server health check failed: {}", e.getClass().getSimpleName());
        }
    }

    private void check(Path directory, boolean recover) {
        try {
            awaitingImport.remove(directory);
            var current = inParallel(projectTools.detected(directory), found -> fresh(directory, found, recover));
            watchers.stream().filter(watcher -> watcher.directory.equals(directory)).forEach(watcher -> watcher.deliver(current));
            if (awaitingImport.contains(directory)) {
                later.run(() -> recheck(directory), properties.markerRecheck());
            }
        } catch (RuntimeException e) {
            log.warn("Language server health check of a project failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Runs each item on its own thread so one tool's recovery pauses never hold up the others; results keep the input order. */
    private static <T, R> List<R> inParallel(List<T> items, Function<T, R> task) {
        return ParallelTasks.run(items, item -> LSP_HEALTH_THREAD, task);
    }

    private void recheck(Path directory) {
        if (watchers.stream().anyMatch(watcher -> watcher.directory.equals(directory))) {
            check(directory, false);
        }
    }

    /** The reason a server of a project is reported FAILED by this monitor itself rather than by the probe, e.g. a missing Gradle output redirect. */
    public Optional<String> failureDetail(Path projectDir, String toolId) {
        return Optional.ofNullable(details.get(detailKey(projectDir, toolId)));
    }

    private static String detailKey(Path projectDir, String toolId) {
        return toolId + "@" + projectDir;
    }

    private DetectedTechnology fresh(Path directory, DetectedTechnology found, boolean recover) {
        var id = found.tool().id();
        details.remove(detailKey(directory, id));
        if (!found.pluginState().serverMatters() || found.serverState() == ToolState.MISSING) {
            return found;
        }
        try {
            var status = probe.probeFresh(id);
            var state = status.state();
            if (healthy(state)) {
                notRepairedByReinstall.remove(id);
                return withRedirectCheck(directory, found, state);
            }
            return found.withFailure(status.detail()).withServerState(recover && state == ToolState.FAILED ? recovered(id) : state);
        } catch (RuntimeException e) {
            log.warn("Probing language server {} failed: {}", id, e.getClass().getSimpleName());
            return found;
        }
    }

    private DetectedTechnology withRedirectCheck(Path directory, DetectedTechnology found, ToolState state) {
        var id = found.tool().id();
        if (!JDTLS.equals(id)) {
            return found.withServerState(state);
        }
        var outcome = GradleOutputRedirectCheck.check(managedTools.stateDirectory(id), directory, channelOf(id));
        var failure = outcome.failure();
        if (outcome.pending()) {
            var since = pendingSince.computeIfAbsent(directory, key -> clock.instant());
            if (since.plus(properties.markerWait()).isAfter(clock.instant())) {
                awaitingImport.add(directory);
            } else {
                failure = Optional.of("Gradle output redirect did not run (marker not written within "
                        + properties.markerWait().toSeconds() + " s)");
            }
        } else {
            pendingSince.remove(directory);
        }
        failure.ifPresent(detail -> {
            details.put(detailKey(directory, id), detail);
            log.warn("Language server {} reported FAILED: {}", id, detail);
        });
        return failure.map(found::withFailure).orElseGet(() -> found.withServerState(state));
    }

    private ToolChannel channelOf(String id) {
        var channel = installer.installedChannel(id);
        return channel == null ? ToolChannel.VERIFIED : channel;
    }

    private ToolState recovered(String id) {
        if (installer.isInstalling()) {
            return ToolState.FAILED;
        }
        try {
            for (int attempt = 1; attempt <= properties.recoveryAttempts(); attempt++) {
                pause.pause(properties.recoveryBackoff());
                var state = probe.probeFresh(id).state();
                log.info("Language server {} retry {}: {}", id, attempt, state);
                if (state != ToolState.FAILED) {
                    return state;
                }
            }
            return reinstalled(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolState.FAILED;
        }
    }

    private static boolean healthy(ToolState state) {
        return state == ToolState.INSTALLED || state == ToolState.RESPONDING;
    }

    /** One try per cooldown, and none after a try that did not help until the tool was seen healthy; the cooldown is recorded before the try. */
    private ToolState reinstalled(String id) {
        var now = clock.instant();
        if (!installer.runsManagedLauncher(id) || notRepairedByReinstall.contains(id) || channelOf(id) == ToolChannel.LATEST) {
            return ToolState.FAILED;
        }
        var claimed = new boolean[1];
        lastReinstall.compute(id, (key, previous) -> {
            claimed[0] = previous == null || !previous.plus(properties.reinstallCooldown()).isAfter(now);
            return claimed[0] ? now : previous;
        });
        if (!claimed[0]) {
            return ToolState.FAILED;
        }
        var result = installer.reinstall(id);
        if (result.outcome() == ToolInstallService.Outcome.BLOCKED) {
            lastReinstall.remove(id);
            return ToolState.FAILED;
        }
        var state = probe.probeFresh(id).state();
        log.info("Language server {} reinstalled: {} ({})", id, result.outcome(), state);
        if (!healthy(state)) {
            notRepairedByReinstall.add(id);
            log.warn("Language server {} still fails after a reinstall; no more automatic reinstalls, check the Languages page", id);
        }
        return state;
    }
}
