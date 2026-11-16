package com.github.krpkbhdn.ccmanager.toolchain;

import static com.github.krpkbhdn.ccmanager.workspace.HostEnvironment.OperatingSystem.LINUX;
import static com.github.krpkbhdn.ccmanager.workspace.HostEnvironment.OperatingSystem.WINDOWS;
import static com.github.krpkbhdn.ccmanager.toolchain.ExecutableLookups.lookup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.toolchain.managed.AllowlistedHttp;
import com.github.krpkbhdn.ccmanager.toolchain.managed.LauncherWriter;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolInstaller;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.ccmanager.workspace.CommandRunner;
import com.github.krpkbhdn.ccmanager.workspace.FakeHostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ToolProbeTest {

    private static final Path BIN = Path.of("usr", "bin");
    private static final ToolchainProperties PROPERTIES =
            new ToolchainProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(30),
                    Duration.ofMinutes(30), Duration.ofSeconds(10), DataSize.ofGigabytes(1), DataSize.ofGigabytes(4), 200_000,
                    Duration.ofSeconds(30), 5, 2000, 500);

    private Clock clock = Clock.systemUTC();
    private final LspHandshake handshake = mock(LspHandshake.class);
    private FakeHostEnvironment host = new FakeHostEnvironment(LINUX, Path.of("home")).withPath(BIN);

    private ToolProbe probe(ToolProbe.VersionCommand command) {
        return new ToolProbe(new ToolCatalog(), lookup(host), host, handshake, PROPERTIES, managedTools(), command, clock);
    }

    private static ManagedToolsDirectory managedTools() {
        return new ManagedToolsDirectory(new AppProperties(ExecutableLookups.DATA_DIR));
    }

    @Test
    void launcherInTheManagedBinFolderReportsTheDownloadedVersion(@TempDir Path dataDir) throws IOException {
        var tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        var version = tools.versionDirectory("kotlin-lsp", "1.2.3");
        Files.createDirectories(version);
        tools.markComplete(version);
        host = host.withFile(tools.binDirectory().resolve("kotlin-lsp"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));
        var probe = new ToolProbe(new ToolCatalog(), new ExecutableLookup(host, tools), host, handshake, PROPERTIES, tools,
                (c, t, env) -> { throw new AssertionError("version command must not run"); }, clock);

        assertThat(probe.probe("kotlin-lsp").version()).isEqualTo("1.2.3");
    }

    @Test
    void routedServerIsHandshakedThroughTheRouterWithItsOwnSystemPathAndWorkspace(@TempDir Path dataDir) throws IOException {
        var tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        var install = tools.versionDirectory("kotlin-lsp", "1.0");
        var probe = routedProbe(tools);

        assertThat(probe.probe("kotlin-lsp").state()).isEqualTo(ToolState.RESPONDING);

        var workspace = org.mockito.ArgumentCaptor.forClass(Path.class);
        @SuppressWarnings("unchecked")
        var command = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(handshake).perform(command.capture(), any(), anyMap(), workspace.capture());
        assertThat(((List<?>) command.getValue()).stream().map(Object::toString).toList()).containsExactly(install.resolve("bin/lsp-router.exe").toString(), "--server-launcher",
                install.resolve("bin/intellij-server.exe").toString(), "--system-path",
                tools.stateDirectory("kotlin-lsp").resolve("probe-system").toString());
        assertThat(workspace.getValue()).isEqualTo(tools.stateDirectory("kotlin-lsp").resolve("probe-workspace")).isEmptyDirectory();
    }

    private ToolProbe routedProbe(ManagedToolsDirectory tools) throws IOException {
        var install = tools.versionDirectory("kotlin-lsp", "1.0");
        Files.createDirectories(install.resolve("bin"));
        Files.writeString(install.resolve("bin/lsp-router.exe"), "x");
        tools.markComplete(install);
        var tool = new ToolCatalog().find("kotlin-lsp").orElseThrow();
        new ManagedToolInstaller(tools, new LauncherWriter(tools), PROPERTIES, new AllowlistedHttp(Set.of(), Duration.ofSeconds(1)),
                LauncherWriter.Platform.WINDOWS).refreshLauncher(tool, tool.downloadSource("windows").orElseThrow());
        var otherCopy = Path.of("other-bin");
        var windows = new FakeHostEnvironment(WINDOWS, Path.of("home")).withPath(otherCopy).withPath(tools.binDirectory())
                .withFile(otherCopy.resolve("kotlin-lsp.cmd")).withFile(tools.binDirectory().resolve("kotlin-lsp.cmd"));
        when(handshake.perform(anyList(), any(), anyMap(), any(Path.class)))
                .thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));
        return new ToolProbe(new ToolCatalog(), new ExecutableLookup(windows, tools), windows, handshake, PROPERTIES, tools,
                (c, t, env) -> { throw new AssertionError("version command must not run"); }, clock);
    }

    @Test
    void staleLauncherIsProbedItselfInsteadOfTheRouter(@TempDir Path dataDir) throws IOException {
        var tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        var probe = routedProbe(tools);
        Files.writeString(tools.binDirectory().resolve("kotlin-lsp.cmd"), "stale launcher");
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.FAILED, "locked"));

        var status = probe.probe("kotlin-lsp");

        assertThat(status.state()).isEqualTo(ToolState.FAILED);
        verify(handshake, never()).perform(anyList(), any(), anyMap(), any(Path.class));
    }

    @Test
    void routedProbeClearsOnlyTheLogsOfItsSystemFolder(@TempDir Path dataDir) throws IOException {
        var tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        var system = tools.stateDirectory("kotlin-lsp").resolve("probe-system");
        Files.createDirectories(system.resolve("system/log/telemetry"));
        Files.writeString(system.resolve("system/log/intellij-server.log"), "old");
        Files.writeString(system.resolve("system/log/telemetry/m.json"), "old");
        Files.createDirectories(system.resolve("system/index"));
        Files.writeString(system.resolve("system/index/indices.enum"), "keep");
        var probe = routedProbe(tools);

        assertThat(probe.probe("kotlin-lsp").state()).isEqualTo(ToolState.RESPONDING);

        assertThat(system.resolve("system/log")).doesNotExist();
        assertThat(system.resolve("system/index/indices.enum")).hasContent("keep");
    }

    @Test
    void routedProbeStillRespondsWhenALogFileCannotBeDeleted(@TempDir Path dataDir) throws IOException {
        var tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        var logs = tools.stateDirectory("kotlin-lsp").resolve("probe-system/system/log");
        Files.createDirectories(logs);
        var held = logs.resolve("intellij-server.log");
        Files.writeString(held, "old");
        var probe = routedProbe(tools);

        assertThat(held.toFile().setReadOnly()).isTrue(); // cannot be deleted on Windows
        assertThat(probe.probe("kotlin-lsp").state()).isEqualTo(ToolState.RESPONDING);
        held.toFile().setWritable(true);
    }

    private static CommandRunner.Result output(int exit, String stdout) {
        return new CommandRunner.Result(exit, stdout, "", false);
    }

    @Test
    void languageServerWithoutVersionArgsHasNoVersionCommandButIsStillHandshaked() {
        host = host.withFile(BIN.resolve("pyright-langserver"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));
        var probe = probe((c, t, env) -> { throw new AssertionError("version command must not run"); });

        var server = probe.probe("pyright");

        assertThat(server.state()).isEqualTo(ToolState.RESPONDING);
        assertThat(server.version()).isEmpty();
        verify(handshake, times(1)).perform(anyList(), any(), anyMap());
    }

    private ToolStatus failedJdtls(com.github.krpkbhdn.ccmanager.toolchain.managed.ToolChannel channel) {
        host = host.withFile(BIN.resolve("jdtls"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.FAILED, "exit 13"));
        var probe = new ToolProbe(new ToolCatalog(), lookup(host), host, handshake, PROPERTIES, managedTools(),
                (c, t, env) -> { throw new AssertionError("version command must not run"); }, clock, id -> channel);
        return probe.probe("jdtls");
    }

    @Test
    void latestChannelHandshakeFailureNamesTheUnverifiedVersion() {
        var status = failedJdtls(com.github.krpkbhdn.ccmanager.toolchain.managed.ToolChannel.LATEST);

        assertThat(status.state()).isEqualTo(ToolState.FAILED);
        assertThat(status.detail()).isEqualTo("Latest jdtls " + status.version() + " (not verified with the output-redirect bundle): exit 13");
    }

    @Test
    void verifiedChannelHandshakeFailureKeepsTheDetail() {
        assertThat(failedJdtls(com.github.krpkbhdn.ccmanager.toolchain.managed.ToolChannel.VERIFIED).detail()).isEqualTo("exit 13");
    }

    @Test
    void versionCheckAndHandshakeRunWithTheLookupSearchPath() {
        var userBin = Path.of("home", ".local", "bin");
        host = host.withFile(userBin.resolve("node"));
        var seen = new java.util.ArrayList<java.util.Map<String, String>>();
        var probe = probe((c, t, env) -> { seen.add(env); return output(0, "1"); });

        probe.probe("node");

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().get("PATH")).startsWith(BIN.toString()).contains(userBin.toString());
    }

    @Test
    void probeAllReusesFreshStatusesAndProbeAllFreshRefreshes() {
        host = host.withFile(BIN.resolve("node"));
        var runs = new AtomicInteger();
        var probe = probe((c, t, env) -> { runs.incrementAndGet(); return output(0, "1"); });

        probe.probeAll();
        probe.probeAll();
        assertThat(runs).hasValue(1);

        probe.probeAllFresh();
        assertThat(runs).hasValue(2);
    }

    @Test
    void probeAllReportsFailedForOneToolWithoutFailingTheRest() {
        host = host.withFile(BIN.resolve("node")).withFile(BIN.resolve("go"));
        var probe = probe((c, t, env) -> {
            if (c.getFirst().endsWith("go")) {
                throw new IllegalArgumentException("secret " + c);
            }
            return output(0, "1");
        });

        var all = probe.probeAll();

        var go = all.stream().filter(s -> s.id().equals("go")).findFirst().orElseThrow();
        assertThat(go.state()).isEqualTo(ToolState.FAILED);
        assertThat(go.detail()).doesNotContain("secret");
        assertThat(all.stream().filter(s -> s.id().equals("node")).findFirst().orElseThrow().state()).isEqualTo(ToolState.INSTALLED);
    }

    @Test
    void probeStartedEarlierNeverOverwritesNewerResult() throws Exception {
        host = host.withFile(BIN.resolve("node"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var probe = probe((c, t, env) -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return output(0, "stale");
            }
            return output(0, "new");
        });
        var stale = CompletableFuture.supplyAsync(() -> probe.probe("node"));
        started.await();

        probe.probeFresh("node");
        release.countDown();
        assertThat(stale.get().version()).isEqualTo("stale");

        assertThat(probe.probe("node").version()).isEqualTo("new");
        assertThat(calls).hasValue(2);
    }

    @Test
    void unsafeShimPathIsReportedOnlyForTheShimFailure() {
        var windows = new FakeHostEnvironment(WINDOWS, Path.of("home")).withPath(Path.of("a&b"))
                .withFile(Path.of("a&b").resolve("node.cmd")).withFile(Path.of("a&b").resolve("go.exe"));
        var probe = new ToolProbe(new ToolCatalog(), lookup(windows), windows, handshake, PROPERTIES, managedTools(),
                (c, t, env) -> { throw new IllegalStateException("other"); }, clock);

        assertThat(probe.probe("node").detail()).isEqualTo("unsafe path for a .cmd launcher");
        assertThatThrownBy(() -> probe.probe("go")).isInstanceOf(IllegalStateException.class).hasMessage("other");
    }

    @Test
    void probeReusesStatusUntilTtlThenRefreshes() {
        host = host.withFile(BIN.resolve("node"));
        var runs = new AtomicInteger();
        var now = new Instant[] {Instant.EPOCH};
        clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        var probe = probe((c, t, env) -> { runs.incrementAndGet(); return output(0, "1"); });

        probe.probe("node");
        probe.probe("node");
        assertThat(runs).hasValue(1);

        probe.probeFresh("node");
        assertThat(runs).hasValue(2);

        now[0] = Instant.EPOCH.plusSeconds(31);
        probe.probe("node");
        assertThat(runs).hasValue(3);
    }

    @Test
    void missingWhenBinaryAbsent() {
        assertThat(probe((c, t, env) -> output(0, "")).probe("node").state()).isEqualTo(ToolState.MISSING);
    }

    @Test
    void installedWithFirstNonEmptyLineAsVersion() {
        host = host.withFile(BIN.resolve("node"));

        var status = probe((c, t, env) -> output(0, "\n  v22.1.0  \nsecond")).probe("node");

        assertThat(status.state()).isEqualTo(ToolState.INSTALLED);
        assertThat(status.version()).isEqualTo("v22.1.0");
    }

    @Test
    void failedWhenVersionCommandFailsAndArgsGiven() {
        host = host.withFile(BIN.resolve("node"));

        var status = probe((c, t, env) -> output(2, "")).probe("node");

        assertThat(status.state()).isEqualTo(ToolState.FAILED);
        assertThat(status.detail()).isNotBlank();
    }

    @Test
    void failedDetailShowsFirstOutputLineWhenVersionCommandExitsNonZero() {
        host = host.withFile(BIN.resolve("rust-analyzer"));

        var status = probe((c, t, env) -> new CommandRunner.Result(1, "", "error: Unknown binary rust-analyzer\nmore", false))
                .probe("rust-analyzer");

        assertThat(status.state()).isEqualTo(ToolState.FAILED);
        assertThat(status.detail()).isEqualTo("error: Unknown binary rust-analyzer");
    }

    @Test
    void installedWithoutVersionWhenNoVersionArgsAndCommandFails() {
        host = host.withFile(BIN.resolve("pyright-langserver"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.NO_RESPONSE_IN_TIME, ""));

        var status = probe((c, t, env) -> { throw new IOException("boom"); }).probe("pyright");

        assertThat(status.state()).isEqualTo(ToolState.INSTALLED);
        assertThat(status.version()).isEmpty();
    }

    @Test
    void handshakeRespondedMapsToResponding() {
        host = host.withFile(BIN.resolve("typescript-language-server"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));

        var status = probe((c, t, env) -> output(0, "4.3.0")).probe("typescript");

        assertThat(status.state()).isEqualTo(ToolState.RESPONDING);
        assertThat(status.version()).isEqualTo("4.3.0");
        verify(handshake).perform(eq(List.of(BIN.resolve("typescript-language-server").toString(), "--stdio")), eq(Duration.ofSeconds(10)), anyMap());
    }

    @Test
    void handshakeTimeoutStaysInstalledAndFailureIsFailed() {
        host = host.withFile(BIN.resolve("typescript-language-server"));
        when(handshake.perform(anyList(), any(), anyMap()))
                .thenReturn(new LspHandshake.Result(LspHandshake.Outcome.NO_RESPONSE_IN_TIME, ""))
                .thenReturn(new LspHandshake.Result(LspHandshake.Outcome.FAILED, "crashed"));
        var probe = probe((c, t, env) -> output(0, "4.3.0"));

        assertThat(probe.probe("typescript").state()).isEqualTo(ToolState.INSTALLED);
        var failed = probe.probeFresh("typescript");
        assertThat(failed.state()).isEqualTo(ToolState.FAILED);
        assertThat(failed.detail()).isEqualTo("crashed");
    }

    @Test
    void prerequisitesAreNeverHandshaked() {
        host = host.withFile(BIN.resolve("node"));
        var probe = probe((c, t, env) -> output(0, "1"));

        probe.probe("node");

        verify(handshake, never()).perform(anyList(), any(), anyMap());
    }

    @Test
    void probeAllKeepsCatalogOrder() {
        assertThat(probe((c, t, env) -> output(0, "")).probeAll()).extracting(ToolStatus::id)
                .containsExactlyElementsOf(new ToolCatalog().all().stream().map(ToolDefinition::id).toList());
    }

    @Test
    void handshakeArgsPerServer() {
        host = host.withFile(BIN.resolve("pyright-langserver")).withFile(BIN.resolve("gopls"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));
        var probe = probe((c, t, env) -> output(0, "1"));

        probe.probe("pyright");
        probe.probe("gopls");

        verify(handshake).perform(eq(List.of(BIN.resolve("pyright-langserver").toString(), "--stdio")), eq(Duration.ofSeconds(10)), anyMap());
        verify(handshake).perform(eq(List.of(BIN.resolve("gopls").toString())), eq(Duration.ofSeconds(10)), anyMap());
    }

    @Test
    void cssServerGetsTheStdioHandshake() {
        host = host.withFile(BIN.resolve("vscode-css-language-server"));
        when(handshake.perform(anyList(), any(), anyMap())).thenReturn(new LspHandshake.Result(LspHandshake.Outcome.RESPONDED, ""));

        probe((c, t, env) -> output(0, "")).probe("css");

        verify(handshake).perform(eq(List.of(BIN.resolve("vscode-css-language-server").toString(), "--stdio")), eq(Duration.ofSeconds(10)), anyMap());
    }

    @Test
    void versionIsReadFromStderrWhenStdoutIsEmpty() {
        host = host.withFile(BIN.resolve("java"));

        var status = probe((c, t, env) -> new CommandRunner.Result(0, "", "openjdk version \"21.0.2\"", false)).probe("java");

        assertThat(status.version()).isEqualTo("openjdk version \"21.0.2\"");
    }
}
