package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.toolchain.managed.AllowlistedHttp;
import com.github.krpkbhdn.ccmanager.toolchain.managed.LauncherWriter;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolInstaller;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.ccmanager.workspace.CommandRunner;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import com.github.krpkbhdn.ccmanager.workspace.PtyCommandRunner;
import com.github.krpkbhdn.ccmanager.workspace.WindowsArgument;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Installs catalog tools with their bundled install command, one install at a time. Callers pass a tool id only. */
@Service
public class ToolInstallService {

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

    /** The temp folder is user-writable too, so cmd.exe is kept from searching it by NoDefaultCurrentDirectoryInExePath in the environment. */
    static final Path NEUTRAL_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));

    private static final Logger log = LoggerFactory.getLogger(ToolInstallService.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    private static final int MAX_LISTED_LEFTOVERS = 3;
    private static final String MANAGED_RESTART_HINT =
            "Installed, but not found yet. Restart Claude sessions and shells that were open before the install; the app itself needs no restart.";
    private static final String PATH_RESTART_HINT = "Installed, but not found on PATH. After a PATH change, restart the app so it is picked up.";

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final ExecutableLookup lookup;
    private final HostEnvironment host;
    private final ToolchainProperties properties;
    private final InstallRunner runner;
    private final ManagedToolsDirectory directory;
    private final ManagedToolInstaller managed;
    private final ReentrantLock installing = new ReentrantLock();
    private final Object threadLock = new Object();
    private Thread installThread;
    private boolean cancelRequested;

    @Autowired
    public ToolInstallService(ToolCatalog catalog, ToolProbe probe, ExecutableLookup lookup, HostEnvironment host,
                              ToolchainProperties properties, ManagedToolsDirectory directory, LauncherWriter launchers) {
        this(catalog, probe, lookup, host, properties, PtyCommandRunner::run, directory,
                new ManagedToolInstaller(directory, launchers, properties,
                        new AllowlistedHttp(Set.copyOf(ToolCatalogValidator.DOWNLOAD_HOSTS), properties.connectTimeout()),
                        LauncherWriter.Platform.current()));
    }

    ToolInstallService(ToolCatalog catalog, ToolProbe probe, ExecutableLookup lookup, HostEnvironment host,
                       ToolchainProperties properties, InstallRunner runner, ManagedToolsDirectory directory,
                       ManagedToolInstaller managed) {
        this.catalog = catalog;
        this.probe = probe;
        this.lookup = lookup;
        this.host = host;
        this.properties = properties;
        this.runner = runner;
        this.directory = directory;
        this.managed = managed;
    }

    /** Brings the launchers of already downloaded tools to the form this app version writes; no download, a failure only keeps the old launcher. */
    @PostConstruct
    void refreshLaunchers() {
        catalog.all().forEach(tool -> tool.downloadSource(osKey()).ifPresent(source -> {
            try {
                managed.refreshLauncher(tool, source.launcher());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not refresh the launcher of {}: {}", tool.id(), e.getClass().getSimpleName());
            }
        }));
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
        return catalog.find(toolId).map(tool -> plan(tool, false));
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
            var current = plan(plan.get().tool(), true);
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
        var source = plan.tool().downloadSource(osKey());
        if (source.isPresent()) {
            return runDownload(plan.tool(), source.get(), onOutput, repair);
        }
        return repair ? blocked(plan.tool().id(), "Only downloaded tools can be repaired") : runCommand(plan, onOutput);
    }

    private InstallResult runDownload(ToolDefinition tool, DownloadSource source, Consumer<String> onOutput, boolean repair) {
        var toolId = tool.id();
        try {
            var installed = repair ? managed.reinstall(tool, source, onOutput) : managed.install(tool, source, onOutput);
            var status = probe.probeFresh(toolId);
            if (status.state() == ToolState.MISSING) {
                return new InstallResult(Outcome.STILL_MISSING, 0, status, MANAGED_RESTART_HINT);
            }
            var message = installed.upToDate() ? tool.name() + " is already up to date (" + installed.version() + ")"
                    : "Installed " + tool.name() + " " + installed.version();
            return new InstallResult(Outcome.INSTALLED, 0, status, message);
        } catch (InterruptedException e) {
            var status = probe.probeFresh(toolId);
            Thread.currentThread().interrupt();
            return new InstallResult(Outcome.FAILED, -1, status, "Install cancelled");
        } catch (IOException | RuntimeException e) {
            return new InstallResult(Outcome.FAILED, -1, probe.probeFresh(toolId), String.valueOf(e.getMessage()));
        }
    }

    private InstallResult runCommand(InstallPlan plan, Consumer<String> onOutput) {
        var toolId = plan.tool().id();
        CommandRunner.Result result;
        try {
            result = runner.run(launchCommand(plan.command()), NEUTRAL_DIRECTORY, properties.installTimeout(),
                    lookup.environment(), onOutput);
        } catch (IOException e) {
            return new InstallResult(Outcome.FAILED, -1, probe.probeFresh(toolId), "Could not run the install command: " + e.getMessage());
        }
        var status = probe.probeFresh(toolId);
        if (result.timedOut()) {
            return new InstallResult(Outcome.FAILED, result.exitCode(), status, "Install timed out after " + properties.installTimeout().toMinutes() + " min");
        }
        if (result.exitCode() != 0) {
            return new InstallResult(Outcome.FAILED, result.exitCode(), status, lastLine(result.stdout()));
        }
        if (status.state() == ToolState.MISSING) {
            return new InstallResult(Outcome.STILL_MISSING, 0, status, PATH_RESTART_HINT);
        }
        return new InstallResult(Outcome.INSTALLED, 0, status, "Installed " + plan.tool().name());
    }

    private InstallPlan plan(ToolDefinition tool, boolean fresh) {
        var os = osKey();
        var command = tool.installCommand(os);
        var download = tool.downloadSource(os).map(source -> downloadPlan(tool, source));
        return new InstallPlan(tool, command, commandLine(command), blocker(tool, os, fresh),
                tool.manualCommand(os), download);
    }

    private DownloadPlan downloadPlan(ToolDefinition tool, DownloadSource source) {
        return new DownloadPlan(URI.create(source.archiveUrl().replace("{version}", "1")).getHost(), source.latest().url(),
                source.archiveUrl(), directory.root().resolve(tool.id()), source.launcher());
    }

    private Optional<String> blocker(ToolDefinition tool, String os, boolean fresh) {
        if (!tool.hasInstallCommand(os) && tool.downloadSource(os).isEmpty()) {
            return Optional.of("This tool cannot be installed automatically on " + os + ".");
        }
        if (tool.prerequisite().isEmpty()) {
            return Optional.empty();
        }
        var state = (fresh ? probe.probeFresh(tool.prerequisite()) : probe.probe(tool.prerequisite())).state();
        if (state == ToolState.INSTALLED || state == ToolState.RESPONDING) {
            return Optional.empty();
        }
        var name = catalog.find(tool.prerequisite()).map(ToolDefinition::name).orElse(tool.prerequisite());
        return Optional.of("Install " + name + " first");
    }

    /** On Windows npm, pnpm, dotnet and friends are .cmd shims, which only cmd.exe starts. */
    private List<String> launchCommand(List<String> argv) {
        if (!host.windows()) {
            return argv;
        }
        var command = new ArrayList<String>(List.of("cmd.exe", "/c"));
        argv.forEach(argument -> command.add(WindowsArgument.quote(argument)));
        return command;
    }

    private String osKey() {
        return switch (host.os()) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
        };
    }

    private static String commandLine(List<String> argv) {
        return String.join(" ", argv.stream().map(argument -> argument.isEmpty() || argument.chars().anyMatch(Character::isWhitespace)
                ? "\"" + argument + "\"" : argument).toList());
    }

    /** The output is already rendered text; only a line with a letter or digit is a message (not a bar or spinner glyph). */
    private static String lastLine(String output) {
        return output.lines().map(String::strip)
                .filter(line -> line.chars().anyMatch(Character::isLetterOrDigit)).reduce((first, second) -> second)
                .orElse("The install command failed");
    }

    private InstallResult blocked(String toolId, String reason) {
        return new InstallResult(Outcome.BLOCKED, -1, new ToolStatus(toolId, ToolState.MISSING, "", reason), reason);
    }
}
