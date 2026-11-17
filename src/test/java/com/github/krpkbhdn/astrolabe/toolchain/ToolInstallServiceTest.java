package com.github.krpkbhdn.astrolabe.toolchain;

import static com.github.krpkbhdn.astrolabe.toolchain.ExecutableLookups.lookup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallRunner;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.Outcome;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolInstaller;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannelStore;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolInstallServiceTest {

    private static final List<String> NPM_INSTALL = List.of("npm", "install", "-g", "some tool");

    private final ToolCatalog catalog = mock(ToolCatalog.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final HostEnvironment host = mock(HostEnvironment.class);
    private final ToolchainProperties properties = mock(ToolchainProperties.class);
    private final List<List<String>> started = new ArrayList<>();
    private CommandRunner.Result result = new CommandRunner.Result(0, "out", "", false);
    private InstallRunner runner = (command, directory, timeout, environment, onOutput) -> {
        started.add(command);
        onOutput.accept("line 1");
        return result;
    };

    private final ManagedToolInstaller installer = mock(ManagedToolInstaller.class);
    private ManagedToolsDirectory directory;

    @BeforeEach
    void setUp(@TempDir Path dataDir) {
        directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.LINUX);
        when(host.home()).thenReturn(Path.of("home"));
        when(properties.installTimeout()).thenReturn(Duration.ofMinutes(5));
        register(tool("node", "Node.js", "", Map.of("linux", List.of("apt", "node"))));
        register(tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL, "windows", NPM_INSTALL)));
        status("node", ToolState.RESPONDING);
        status("ts", ToolState.MISSING);
    }

    @Test
    void blocksWhenPrerequisiteIsMissing() {
        status("node", ToolState.MISSING);

        var plan = service().plan("ts").orElseThrow();
        var installed = service().install("ts", line -> {});

        assertThat(plan.blocker()).contains("Install Node.js first");
        assertThat(installed.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(installed.message()).isEqualTo("Install Node.js first");
        assertThat(started).isEmpty();
    }

    @Test
    void blocksWhenThereIsNoCommandForTheOs() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.MACOS);

        var plan = service().plan("ts").orElseThrow();

        assertThat(plan.blocker()).contains("This tool cannot be installed automatically on macos.");
        assertThat(service().install("ts", line -> {}).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(started).isEmpty();
    }

    @Test
    void planDisplaysQuotedCommandLine() {
        assertThat(service().plan("ts").orElseThrow().commandLine()).isEqualTo("npm install -g \"some tool\"");
    }

    @Test
    void unknownToolHasNoPlanAndIsBlocked() {
        assertThat(service().plan("nope")).isEmpty();
        assertThat(service().install("nope", line -> {}).outcome()).isEqualTo(Outcome.BLOCKED);
    }

    @Test
    void successStreamsOutputAndReprobes() {
        when(probe.probeFresh("ts")).thenReturn(new ToolStatus("ts", ToolState.RESPONDING, "5.0", ""));
        var lines = new ArrayList<String>();

        var installed = service().install("ts", lines::add);

        assertThat(installed.outcome()).isEqualTo(Outcome.INSTALLED);
        assertThat(installed.status().state()).isEqualTo(ToolState.RESPONDING);
        assertThat(lines).containsExactly("line 1");
        assertThat(started).containsExactly(NPM_INSTALL);
    }

    @Test
    void windowsRunsThroughCmdWithQuotedArguments() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.WINDOWS);
        when(host.windows()).thenReturn(true);
        when(probe.probeFresh("ts")).thenReturn(new ToolStatus("ts", ToolState.INSTALLED, "", ""));

        service().install("ts", line -> {});

        assertThat(started).containsExactly(List.of("cmd.exe", "/c", "npm", "install", "-g", "\"some tool\""));
    }

    @Test
    void exitCodeZeroButMissingIsStillMissing() {
        var installed = service().install("ts", line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.STILL_MISSING);
        assertThat(installed.message()).isEqualTo("Installed, but not found on PATH. After a PATH change, restart the app so it is picked up.");
    }

    @Test
    void nonZeroExitFailsWithLastNonEmptyLine() {
        result = new CommandRunner.Result(1, "first\nnpm ERR! boom\n\n", "", false);

        var installed = service().install("ts", line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(installed.exitCode()).isEqualTo(1);
        assertThat(installed.message()).isEqualTo("npm ERR! boom");
    }

    @Test
    void failureMessageSkipsRenderedSpinnerLines() {
        result = new CommandRunner.Result(1, "npm ERR! boom\n  ⠋ ⠙\n\n", "", false);

        assertThat(service().install("ts", line -> {}).message()).isEqualTo("npm ERR! boom");
    }

    @Test
    void secondConcurrentInstallIsRejected() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        runner = (command, directory, timeout, environment, onOutput) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return result;
        };
        var service = service();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> service.install("ts", line -> {}));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = service.install("ts", line -> {});
            release.countDown();

            assertThat(second.outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(second.message()).isEqualTo("Another install is running");
            assertThat(first.get(5, TimeUnit.SECONDS).outcome()).isNotEqualTo(Outcome.BLOCKED);
        }
    }

    @Test
    void commandComesOnlyFromTheCatalogNotFromTheCaller() {
        service().install("ts; rm -rf /", line -> {});
        service().install("ts", line -> {});

        assertThat(started).containsExactly(NPM_INSTALL);
    }

    @Test
    void installRunsInTheTempDirectoryNotHome() {
        var directories = new ArrayList<Path>();
        runner = (command, directory, timeout, environment, onOutput) -> {
            directories.add(directory);
            return result;
        };

        service().install("ts", line -> {});

        assertThat(directories).containsExactly(Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Test
    void installRunsWithTheLookupSearchPath() {
        var nodeDirectory = Path.of("pf", "nodejs");
        var windows = new FakeHostEnvironment(HostEnvironment.OperatingSystem.WINDOWS, Path.of("home")).withPath(Path.of("old"))
                .withEnv("ProgramFiles", "pf").withFile(nodeDirectory.resolve("node.exe"));
        var seen = new ArrayList<Map<String, String>>();
        runner = (command, directory, timeout, environment, onOutput) -> {
            seen.add(environment);
            return result;
        };

        new ToolInstallService(catalog, probe, lookup(windows), host, properties, runner, directory, installer, new ToolChannelStore(directory)).install("ts", line -> {});

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().get("PATH")).startsWith("old").contains(nodeDirectory.toString());
        assertThat(seen.getFirst()).containsEntry("NoDefaultCurrentDirectoryInExePath", "1");
    }

    @Test
    void stopInterruptsARunningInstallAndReleasesTheLock() throws Exception {
        var entered = new CountDownLatch(1);
        runner = (command, directory, timeout, environment, onOutput) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new IOException("interrupted", e);
            }
            return result;
        };
        var service = service();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> service.install("ts", line -> {}));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.isInstalling()).isTrue();

            service.stop();

            assertThat(running.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.FAILED);
            assertThat(service.isInstalling()).isFalse();
        }
    }

    private ToolInstallService service() {
        return new ToolInstallService(catalog, probe, lookup(host), host, properties, runner, directory, installer, new ToolChannelStore(directory));
    }

    private static final DownloadSource DOWNLOAD = new DownloadSource(
            new LatestSource(LatestSource.Kind.TEXT_FILE, "https://download.eclipse.org/x/latest.txt", "v([0-9.]+)"),
            "https://download.eclipse.org/x/{version}/x.tar.gz", ArchiveKind.TAR_GZ, new LauncherSpec(LauncherSpec.Kind.BINARY, "bin/x"));

    private void registerDownloadTool() {
        register(new ToolDefinition("dl", "Downloaded", "g", ToolKind.LANGUAGE_SERVER, "dl", List.of(), "", "", List.of(),
                Map.of(), "https://docs", Map.of(), Map.of("linux", DOWNLOAD)));
        status("dl", ToolState.INSTALLED);
    }

    @Test
    void downloadPlanHasNoCommandAndNoBlocker() {
        registerDownloadTool();

        var plan = service().plan("dl").orElseThrow();

        assertThat(plan.command()).isEmpty();
        assertThat(plan.commandLine()).isEmpty();
        assertThat(plan.blocker()).isEmpty();
        var download = plan.download().orElseThrow();
        assertThat(download.host()).isEqualTo("download.eclipse.org");
        assertThat(download.installDirectory()).isEqualTo(directory.root().resolve("dl"));
        assertThat(download.archiveUrlTemplate()).contains("{version}");
    }

    @Test
    void downloadForAnotherOsOnlyBlocksWithTheNoCommandReason() {
        registerDownloadTool();
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.MACOS);

        var plan = service().plan("dl").orElseThrow();

        assertThat(plan.download()).isEmpty();
        assertThat(plan.blocker()).contains("This tool cannot be installed automatically on macos.");
    }

    @Test
    void downloadInstallUsesTheManagedInstallerNotTheCommandRunner() throws Exception {
        registerDownloadTool();
        when(installer.install(any(), any(), any(), any())).thenReturn(new ManagedToolInstaller.Installed("1.2", false, Path.of("l")));

        var installed = service().install("dl", line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.INSTALLED);
        assertThat(installed.message()).isEqualTo("Installed Downloaded 1.2");
        assertThat(started).isEmpty();
    }

    @Test
    void downloadInstallOfTheNewestVersionSaysUpToDate() throws Exception {
        registerDownloadTool();
        when(installer.install(any(), any(), any(), any())).thenReturn(new ManagedToolInstaller.Installed("1.2", true, Path.of("l")));

        assertThat(service().install("dl", line -> {}).message()).isEqualTo("Downloaded is already up to date (1.2)");
    }

    @Test
    void downloadFailureIsReportedAsFailed() throws Exception {
        registerDownloadTool();
        when(installer.install(any(), any(), any(), any())).thenThrow(new IOException("SHA-256 mismatch"));

        var installed = service().install("dl", line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(installed.message()).isEqualTo("SHA-256 mismatch");
    }

    @Test
    void interruptedDownloadFailsAsCancelledAndKeepsTheInterruptFlag() throws Exception {
        registerDownloadTool();
        when(installer.install(any(), any(), any(), any())).thenThrow(new InterruptedException());

        var installed = service().install("dl", line -> {});

        assertThat(installed.message()).isEqualTo("Install cancelled");
        assertThat(Thread.interrupted()).isTrue();
        assertThat(service().isInstalling()).isFalse();
    }

    @Test
    void reinstallUsesTheRepairPathOfTheManagedInstaller() throws Exception {
        registerDownloadTool();
        when(installer.reinstall(any(), any(), any(), any())).thenReturn(new ManagedToolInstaller.Installed("1.2", false, Path.of("l")));

        var repaired = service().reinstall("dl");

        assertThat(repaired.outcome()).isEqualTo(Outcome.INSTALLED);
        verify(installer, never()).install(any(), any(), any(), any());
    }

    @Test
    void failedReinstallIsReportedAndNothingIsUninstalled() throws Exception {
        registerDownloadTool();
        var version = directory.versionDirectory("dl", "1.0");
        Files.createDirectories(version);
        directory.markComplete(version);
        when(installer.reinstall(any(), any(), any(), any())).thenThrow(new IOException("offline"));

        var repaired = service().reinstall("dl");

        assertThat(repaired.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(service().isManaged("dl")).isTrue();
    }

    @Test
    void reinstallOfACommandInstalledToolIsBlocked() {
        assertThat(service().reinstall("ts").outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(started).isEmpty();
    }

    @Test
    void reinstallWhileAnotherInstallRunsIsBlockedWithoutTouchingTheInstaller() throws Exception {
        registerDownloadTool();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(installer.install(any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            release.await();
            return new ManagedToolInstaller.Installed("1.2", false, Path.of("l"));
        });
        var service = service();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> service.install("dl", line -> {}));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.reinstall("dl").outcome()).isEqualTo(Outcome.BLOCKED);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        verify(installer, never()).reinstall(any(), any(), any(), any());
    }

    @Test
    void onlyTheLauncherInTheManagedBinFolderCountsAsTheManagedCopy() throws Exception {
        registerDownloadTool();
        var version = directory.versionDirectory("dl", "1.0");
        Files.createDirectories(version);
        directory.markComplete(version);
        var managedLauncher = directory.binDirectory().resolve("dl");
        var pathCopy = Path.of("usr", "bin").toAbsolutePath().resolve("dl");
        when(host.pathDirectories()).thenReturn(List.of(pathCopy.getParent()));
        when(host.isFile(any())).thenAnswer(call -> pathCopy.equals(call.getArgument(0)));
        var service = new ToolInstallService(catalog, probe, new ExecutableLookup(host, directory), host, properties, runner, directory, installer, new ToolChannelStore(directory));

        assertThat(service.runsManagedLauncher("dl")).isFalse();

        when(host.isFile(any())).thenAnswer(call -> managedLauncher.equals(call.getArgument(0)));
        assertThat(service.runsManagedLauncher("dl")).isTrue();
    }

    @Test
    void uninstallDeletesTheToolAndItsLauncherAndIsManagedFollows() throws Exception {
        registerDownloadTool();
        var version = directory.versionDirectory("dl", "1.0");
        Files.createDirectories(version);
        directory.markComplete(version);
        Files.createDirectories(directory.binDirectory());
        var launcher = Files.writeString(directory.binDirectory().resolve("dl"), "x");
        assertThat(service().isManaged("dl")).isTrue();

        var removed = service().uninstall("dl");

        assertThat(removed.outcome()).isEqualTo(Outcome.INSTALLED);
        assertThat(removed.message()).isEqualTo("Removed Downloaded");
        assertThat(launcher).doesNotExist();
        assertThat(service().isManaged("dl")).isFalse();
        assertThat(service().uninstall("nope").outcome()).isEqualTo(Outcome.BLOCKED);
    }

    @Test
    void uninstallNamesWhatCouldNotBeRemoved() throws Exception {
        registerDownloadTool();
        Files.createDirectories(directory.binDirectory().resolve("dl").resolve("inner"));

        var removed = service().uninstall("dl");

        assertThat(removed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(removed.message()).contains("Still in use").contains(directory.binDirectory().resolve("dl").toString());
    }

    @Test
    void managedInstallThatIsStillMissingTellsToRestartSessionsNotTheApp() throws Exception {
        registerDownloadTool();
        status("dl", ToolState.MISSING);
        when(probe.probeFresh("dl")).thenReturn(new ToolStatus("dl", ToolState.MISSING, "", ""));
        when(installer.install(any(), any(), any(), any())).thenReturn(new ManagedToolInstaller.Installed("1.2", false, Path.of("l")));

        var installed = service().install("dl", line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.STILL_MISSING);
        assertThat(installed.message()).contains("Restart Claude sessions and shells").contains("app itself needs no restart");
    }

    @Test
    void cancelInterruptsOnlyTheRunningInstallAndClearsTheInterruptFlag() throws Exception {
        registerDownloadTool();
        var entered = new CountDownLatch(1);
        when(installer.install(any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        var service = service();
        service.cancel();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> {
                var result = service.install("dl", line -> {});
                return new Object[] {result, Thread.currentThread().isInterrupted()};
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            service.cancel();

            var outcome = running.get(5, TimeUnit.SECONDS);
            assertThat(((ToolInstallService.InstallResult) outcome[0]).message()).isEqualTo("Install cancelled");
            assertThat((boolean) outcome[1]).isFalse();
            assertThat(service.isInstalling()).isFalse();
        }
    }

    private void register(ToolDefinition tool) {
        when(catalog.find(tool.id())).thenReturn(Optional.of(tool));
    }

    private void status(String id, ToolState state) {
        var status = new ToolStatus(id, state, "", "");
        when(probe.probe(id)).thenReturn(status);
        when(probe.probeFresh(id)).thenReturn(status);
    }

    private static ToolDefinition tool(String id, String name, String prerequisite, Map<String, List<String>> install) {
        return new ToolDefinition(id, name, "g", ToolKind.LANGUAGE_SERVER, id, List.of(), "", prerequisite, List.of(), install, "https://docs", Map.of(), null);
    }

    @Test
    void stopWaitsUntilTheBlockedInstallObservedTheInterruptAndReleasedTheLock() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        runner = (command, directory, timeout, environment, onOutput) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000_000L);
                interrupted.set(true);
            }
            return result;
        };
        var service = service();
        var install = new Thread(() -> service.install("ts", line -> {}));
        install.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        service.stop();

        assertThat(interrupted).isTrue();
        assertThat(service.isInstalling()).isFalse();
    }

    @Test
    void manualInstallIsShownInThePlanButNeverRun() {
        register(new ToolDefinition("manual", "Manual", "g", ToolKind.LANGUAGE_SERVER, "manual", List.of(), "", "", List.of(),
                Map.of(), "https://docs", Map.of("linux", "curl https://x | sudo sh"), null));
        status("manual", ToolState.MISSING);

        var plan = service().plan("manual").orElseThrow();
        var installed = service().install("manual", line -> {});

        assertThat(plan.manualCommand()).isEqualTo("curl https://x | sudo sh");
        assertThat(plan.command()).isEmpty();
        assertThat(plan.blocker()).contains("This tool cannot be installed automatically on linux.");
        assertThat(installed.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(started).isEmpty();
    }

    @Test
    void planWithoutManualInstallHasEmptyManualCommand() {
        assertThat(service().plan("ts").orElseThrow().manualCommand()).isEmpty();
    }

    private void registerPinnedTool() {
        var pinned = new DownloadSource(DOWNLOAD.latest(), DOWNLOAD.archiveUrl(), DOWNLOAD.archive(), DOWNLOAD.launcher(),
                new PinnedVersion("1.0", "https://example.com/dl.zip", "0".repeat(64)));
        register(new ToolDefinition("pin", "Pinned", "g", ToolKind.LANGUAGE_SERVER, "pin", List.of(), "", "", List.of(),
                Map.of(), "https://docs", Map.of(), Map.of("linux", pinned)));
        status("pin", ToolState.INSTALLED);
    }

    @Test
    void selectingAChannelOnlyStoresItAndNotifies() {
        registerPinnedTool();
        var notified = new AtomicInteger();
        var service = service();
        service.onChannelChange(notified::incrementAndGet);

        assertThat(service.selectedChannel("pin")).isEqualTo(ToolChannel.VERIFIED);
        service.selectChannel("pin", ToolChannel.LATEST);

        assertThat(service.selectedChannel("pin")).isEqualTo(ToolChannel.LATEST);
        assertThat(notified).hasValue(1);
        verifyNoInteractions(installer);
    }

    @Test
    void channelsAreRejectedForUnknownAndUnsupportedTools() {
        registerDownloadTool();
        var service = service();

        assertThatThrownBy(() -> service.selectChannel("nope", ToolChannel.LATEST)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.selectChannel("dl", ToolChannel.LATEST)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.selectedChannel("dl")).isEqualTo(ToolChannel.VERIFIED);
    }

    @Test
    void installUsesTheSelectedChannel() throws Exception {
        registerPinnedTool();
        when(installer.install(any(), any(), any(), any())).thenReturn(new ManagedToolInstaller.Installed("1.0", false, Path.of("l")));
        var service = service();
        service.selectChannel("pin", ToolChannel.LATEST);

        service.install("pin", line -> {});

        verify(installer).install(any(), any(), eq(ToolChannel.LATEST), any());
    }

    @Test
    void installedChannelIsVerifiedForAnUnknownTool() {
        assertThat(service().installedChannel("nope")).isEqualTo(ToolChannel.VERIFIED);
    }
}
