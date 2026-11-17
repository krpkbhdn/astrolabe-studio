package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ToolChannel;
import com.github.krpkbhdn.ccmanager.workspace.ScheduledSweep;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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

    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
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
    private final ManagedToolsDirectory managedTools;

    @Autowired
    public LspHealthMonitor(ProjectToolsService projectTools, ToolProbe probe, ToolInstallService installer,
                            LspHealthProperties properties, ManagedToolsDirectory managedTools) {
        this(projectTools, probe, installer, properties, managedTools, Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()));
    }

    LspHealthMonitor(ProjectToolsService projectTools, ToolProbe probe, ToolInstallService installer,
                     LspHealthProperties properties, ManagedToolsDirectory managedTools, Clock clock, Pause pause) {
        super("lsp-health", properties.interval());
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
            watchers.stream().map(watcher -> watcher.directory).distinct().forEach(directory -> check(directory, true));
        } catch (RuntimeException | Error e) {
            log.warn("Language server health check failed: {}", e.getClass().getSimpleName());
        }
    }

    private void check(Path directory, boolean recover) {
        try {
            var current = projectTools.detected(directory).stream().map(found -> fresh(directory, found, recover)).toList();
            watchers.stream().filter(watcher -> watcher.directory.equals(directory)).forEach(watcher -> watcher.deliver(current));
        } catch (RuntimeException e) {
            log.warn("Language server health check of a project failed: {}", e.getClass().getSimpleName());
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
            var state = probe.probeFresh(id).state();
            if (healthy(state)) {
                notRepairedByReinstall.remove(id);
                return found.withServerState(withRedirectCheck(directory, id, state));
            }
            return found.withServerState(recover && state == ToolState.FAILED ? recovered(id) : state);
        } catch (RuntimeException e) {
            log.warn("Probing language server {} failed: {}", id, e.getClass().getSimpleName());
            return found;
        }
    }

    private ToolState withRedirectCheck(Path directory, String id, ToolState state) {
        if (!JDTLS.equals(id)) {
            return state;
        }
        var failure = GradleOutputRedirectCheck.failure(managedTools.stateDirectory(id), directory, channelOf(id));
        failure.ifPresent(detail -> details.put(detailKey(directory, id), detail));
        return failure.isPresent() ? ToolState.FAILED : state;
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
        var previous = lastReinstall.get(id);
        if (!installer.runsManagedLauncher(id) || notRepairedByReinstall.contains(id) || channelOf(id) == ToolChannel.LATEST
                || previous != null && previous.plus(properties.reinstallCooldown()).isAfter(now)) {
            return ToolState.FAILED;
        }
        lastReinstall.put(id, now);
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
