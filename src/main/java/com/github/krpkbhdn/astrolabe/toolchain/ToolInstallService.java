package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.managed.AllowlistedHttp;
import com.github.krpkbhdn.astrolabe.toolchain.managed.LauncherWriter;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolInstaller;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannelStore;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.PtyCommandRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Installs catalog tools with their bundled install command, one install at a time. Callers pass a tool id only. */
@Service
public class ToolInstallService implements ToolProbe.InstalledChannels {

    public enum Outcome { INSTALLED, FAILED, BLOCKED, STILL_MISSING }

    /** @param commandLine display text for the confirm dialog; the argv in command is what runs; manualCommand is display-only and never run */
    public record InstallPlan(ToolDefinition tool, List<String> command, String commandLine, Optional<String> blocker,
                              String manualCommand, Optional<DownloadPlan> download) {}

    /** What a download install will fetch and where it goes; installDirectory is the tool's own absolute folder {@code tools/<id>}, under which each version is unpacked. */
    public record DownloadPlan(String host, String latestUrl, String archiveUrlTemplate, Path installDirectory,
                               LauncherSpec launcher) {}

    public record InstallResult(Outcome outcome, int exitCode, ToolStatus status, String message) {}

    /** Runs an argv in a directory; output is the whole text so far on every chunk. */
    @FunctionalInterface
    interface InstallRunner {
        CommandRunner.Result run(List<String> command, Path directory, Duration timeout, Map<String, String> environment,
                                 Consumer<String> onOutput) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(ToolInstallService.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    private static final int MAX_LISTED_LEFTOVERS = 3;
    private static final String MANAGED_RESTART_HINT =
            "Installed, but not found yet. Restart Claude sessions and shells that were open before the install; the app itself needs no restart.";

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final ExecutableLookup lookup;
    private final ToolInstallPlanner planner;
    private final CommandInstall commandInstall;
    private final ManagedToolsDirectory directory;
    private final ManagedToolInstaller managed;
    private final ToolChannelStore channels;
    private final List<Runnable> channelListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock installing = new ReentrantLock();
    private final Object threadLock = new Object();
    private Thread installThread;
    private boolean cancelRequested;

    @Autowired
    public ToolInstallService(ToolCatalog catalog, ToolProbe probe, ExecutableLookup lookup, HostEnvironment host,
                              ToolchainProperties properties, ManagedToolsDirectory directory, LauncherWriter launchers,
                              ToolChannelStore channels) {
        this(catalog, probe, lookup, host, properties, PtyCommandRunner::run, directory,
                new ManagedToolInstaller(directory, launchers, properties,
                        new AllowlistedHttp(Set.copyOf(ToolCatalogValidator.DOWNLOAD_HOSTS), properties.connectTimeout()),
                        LauncherWriter.Platform.current()), channels);
    }

    ToolInstallService(ToolCatalog catalog, ToolProbe probe, ExecutableLookup lookup, HostEnvironment host,
                       ToolchainProperties properties, InstallRunner runner, ManagedToolsDirectory directory,
                       ManagedToolInstaller managed, ToolChannelStore channels) {
        this.catalog = catalog;
        this.probe = probe;
        this.lookup = lookup;
        this.planner = new ToolInstallPlanner(catalog, probe, host, directory);
        this.commandInstall = new CommandInstall(runner, lookup, host, properties, probe);
        this.directory = directory;
        this.managed = managed;
        this.channels = channels;
    }

    /** Brings the launchers of already downloaded tools to the form this app version writes; no download, a failure only keeps the old launcher. */
    @PostConstruct
    void refreshLaunchers() {
        catalog.all().forEach(tool -> tool.downloadSource(planner.osKey()).ifPresent(source -> {
            try {
                managed.refreshLauncher(tool, source);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not refresh the launcher of {}: {}", tool.id(), e.getClass().getSimpleName());
            }
        }));
    }

    /** The channel the next install, update or repair uses; always VERIFIED for a tool that has no pinned version. */
    public ToolChannel selectedChannel(String toolId) {
        return catalog.find(toolId).filter(ToolDefinition::supportsChannels).map(tool -> channels.selected(toolId))
                .orElse(ToolChannel.VERIFIED);
    }

    /** Only remembers the choice; nothing is installed and no launcher is rewritten until the next install or update. */
    public void selectChannel(String toolId, ToolChannel channel) {
        var tool = catalog.find(toolId).orElseThrow(() -> new IllegalArgumentException("Unknown tool " + toolId));
        if (!tool.supportsChannels()) {
            throw new IllegalArgumentException(tool.name() + " has no channels");
        }
        try {
            channels.select(toolId, channel);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        channelListeners.forEach(Runnable::run);
    }

    /** The channel the installed copy came from; VERIFIED when it is not installed or predates channels. */
    @Override
    public ToolChannel installedChannel(String toolId) {
        return catalog.find(toolId).map(managed::installedChannel).orElse(ToolChannel.VERIFIED);
    }

    /** Called after selectChannel stored a choice, on the calling thread. */
    public void onChannelChange(Runnable listener) {
        channelListeners.add(listener);
    }

    /** True when this app downloaded the tool, so it can be updated and removed here. */
    public boolean isManaged(String toolId) {
        return catalog.find(toolId).isPresent() && directory.installedVersion(toolId).isPresent();
    }

    /** Deletes the downloaded tool and its launcher; Outcome.INSTALLED means the removal went through, and the status is probed again. */
    public InstallResult uninstall(String toolId) {
        var tool = catalog.find(toolId);
        if (tool.isEmpty()) {
            return blocked(toolId, "Unknown tool " + toolId);
        }
        if (!installing.tryLock()) {
            return blocked(toolId, "Another install is running");
        }
        try {
            var leftovers = directory.deleteTool(toolId, List.of(tool.get().binary()));
            var status = probe.probeFresh(toolId);
            return leftovers.isEmpty() ? new InstallResult(Outcome.INSTALLED, 0, status, "Removed " + tool.get().name())
                    : new InstallResult(Outcome.FAILED, -1, status, "Could not remove everything. Close sessions using it and try again. Still in use: "
                            + leftovers.stream().limit(MAX_LISTED_LEFTOVERS).map(Path::toString).collect(Collectors.joining(", "))
                            + (leftovers.size() > MAX_LISTED_LEFTOVERS ? " and " + (leftovers.size() - MAX_LISTED_LEFTOVERS) + " more" : ""));
        } finally {
            installing.unlock();
        }
    }

    /** True when the binary that runs for this tool now is the launcher this app wrote, not a copy found on PATH. */
    public boolean runsManagedLauncher(String toolId) {
        return isManaged(toolId) && lookup.find(catalog.find(toolId).orElseThrow().binary())
                .filter(found -> found.toAbsolutePath().normalize().startsWith(directory.binDirectory())).isPresent();
    }

    public boolean isInstalling() {
        return installing.isLocked();
    }

    /** Interrupts a running install, which kills its process tree, and waits briefly so the kill finishes before the JVM exits. */
    @PreDestroy
    void stop() {
        Thread thread;
        synchronized (threadLock) {
            thread = installThread;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Optional<InstallPlan> plan(String toolId) {
        return catalog.find(toolId).map(tool -> planner.plan(tool, false));
    }

    public InstallResult install(String toolId, Consumer<String> onOutput) {
        return install(toolId, onOutput, false);
    }

    /**
     * Repairs a downloaded tool in one locked step: a verified fresh copy replaces the old one only after it is unpacked,
     * so on any failure (also BLOCKED when another install runs) the old installation stays as it was.
     */
    public InstallResult reinstall(String toolId) {
        return install(toolId, output -> { }, true);
    }

    private InstallResult install(String toolId, Consumer<String> onOutput, boolean repair) {
        var plan = plan(toolId);
        if (plan.isEmpty()) {
            return blocked(toolId, "Unknown tool " + toolId);
        }
        if (!installing.tryLock()) {
            return blocked(toolId, "Another install is running");
        }
        synchronized (threadLock) {
            installThread = Thread.currentThread();
        }
        try {
            // re-planned under the lock so a prerequisite installed meanwhile counts
            var current = planner.plan(plan.get().tool(), true);
            if (current.blocker().isPresent()) {
                return blocked(toolId, current.blocker().get());
            }
            return run(current, onOutput, repair);
        } finally {
            synchronized (threadLock) {
                installThread = null;
                if (cancelRequested) {
                    cancelRequested = false;
                    Thread.interrupted(); // the thread may be a pooled one, a leftover flag would hit its next task
                }
            }
            installing.unlock();
        }
    }

    /** Interrupts the install that is running now, if any, and returns at once; the install then ends as "Install cancelled". */
    public void cancel() {
        synchronized (threadLock) {
            if (installThread != null) {
                cancelRequested = true;
                installThread.interrupt();
            }
        }
    }

    private InstallResult run(InstallPlan plan, Consumer<String> onOutput, boolean repair) {
        var source = plan.tool().downloadSource(planner.osKey());
        if (source.isPresent()) {
            return runDownload(plan.tool(), source.get(), onOutput, repair);
        }
        return repair ? blocked(plan.tool().id(), "Only downloaded tools can be repaired") : commandInstall.runCommand(plan, onOutput);
    }

    private InstallResult runDownload(ToolDefinition tool, DownloadSource source, Consumer<String> onOutput, boolean repair) {
        var toolId = tool.id();
        try {
            var installed = repair ? managed.reinstall(tool, source, selectedChannel(toolId), onOutput)
                    : managed.install(tool, source, selectedChannel(toolId), onOutput);
            var status = probe.probeFresh(toolId);
            if (status.state() == ToolState.MISSING) {
                return new InstallResult(Outcome.STILL_MISSING, 0, status, MANAGED_RESTART_HINT);
            }
            var message = installed.upToDate() ? tool.name() + " is already up to date (" + installed.version() + ")"
                    : "Installed " + tool.name() + " " + installed.version();
            return new InstallResult(Outcome.INSTALLED, 0, status, message);
        } catch (InterruptedException e) {
            return commandInstall.cancelled(toolId);
        } catch (IOException | RuntimeException e) {
            return new InstallResult(Outcome.FAILED, -1, probe.probeFresh(toolId), String.valueOf(e.getMessage()));
        }
    }

    private InstallResult blocked(String toolId, String reason) {
        return new InstallResult(Outcome.BLOCKED, -1, new ToolStatus(toolId, ToolState.MISSING, "", reason), reason);
    }
}
