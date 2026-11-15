package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LspHealthMonitorTest {

    private static final class Monitor extends LspHealthMonitor {
        Monitor(ProjectToolsService tools, ToolProbe probe, ToolInstallService installer, LspHealthProperties properties, Clock clock, Pause pause) {
            super(tools, probe, installer, properties, clock, pause);
        }

        void begin() { start(); }

        void end() throws InterruptedException { stop(); }
    }

    private static final Path DIR = Path.of("project");
    private static final Duration COOLDOWN = Duration.ofHours(1);

    private final ProjectToolsService projectTools = mock(ProjectToolsService.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final ToolInstallService installer = mock(ToolInstallService.class);
    private final ToolDefinition go = tool("go");
    private final ToolDefinition rust = tool("rust");
    private final List<Duration> pauses = new ArrayList<>();
    private final List<List<DetectedTechnology>> delivered = new ArrayList<>();
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private Monitor monitor;

    @BeforeEach
    void setUp() {
        var properties = new LspHealthProperties(Duration.ofMinutes(5), 2, Duration.ofSeconds(10), COOLDOWN);
        var clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        monitor = new Monitor(projectTools, probe, installer, properties, clock, pauses::add);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        monitor.end();
    }

    private static ToolDefinition tool(String id) {
        return new ToolDefinition(id, id, "G", ToolKind.LANGUAGE_SERVER, id, List.of(), "", "", List.of(), Map.of(), "", Map.of(), null);
    }

    private void detect(DetectedTechnology... found) {
        when(projectTools.detected(DIR)).thenReturn(List.of(found));
    }

    private void probes(ToolDefinition tool, ToolState... states) {
        var first = new ToolStatus(tool.id(), states[0], "", "");
        var rest = new ToolStatus[states.length - 1];
        for (int i = 1; i < states.length; i++) {
            rest[i - 1] = new ToolStatus(tool.id(), states[i], "", "");
        }
        when(probe.probeFresh(tool.id())).thenReturn(first, rest);
    }

    private ToolInstallService.InstallResult installed() {
        return new ToolInstallService.InstallResult(ToolInstallService.Outcome.INSTALLED, 0, null, "");
    }

    @Test
    void deliversOnlyWhenTheStateChanged() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.RESPONDING, ToolState.RESPONDING, ToolState.INSTALLED);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();
        monitor.sweep();
        monitor.sweep();

        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0)).containsExactly(new DetectedTechnology(go, ToolState.RESPONDING));
        assertThat(delivered.get(1)).containsExactly(new DetectedTechnology(go, ToolState.INSTALLED));
    }

    @Test
    void checkNowDeliversTheFirstStateWithoutRecovery() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        monitor.watch(DIR, delivered::add);

        monitor.checkNow(DIR);

        assertThat(delivered).containsExactly(List.of(new DetectedTechnology(go, ToolState.FAILED)));
        assertThat(pauses).isEmpty();
    }

    @Test
    void missingToolsAreNotProbed() {
        detect(new DetectedTechnology(go, ToolState.MISSING));
        monitor.watch(DIR, delivered::add);

        monitor.checkNow(DIR);

        verify(probe, never()).probeFresh(anyString());
        assertThat(delivered).containsExactly(List.of(new DetectedTechnology(go, ToolState.MISSING)));
    }

    @Test
    void failedToolRecoversOnRetry() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED, ToolState.FAILED, ToolState.RESPONDING);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered).containsExactly(List.of(new DetectedTechnology(go, ToolState.RESPONDING)));
        assertThat(pauses).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        verify(installer, never()).reinstall(anyString());
    }

    @Test
    void managedFailedToolIsRepairedOnceUntilTheCooldownPasses() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED, ToolState.FAILED, ToolState.FAILED, ToolState.RESPONDING, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        when(installer.reinstall("go")).thenReturn(installed());
        monitor.watch(DIR, delivered::add);

        monitor.sweep();
        monitor.sweep();
        verify(installer, times(1)).reinstall("go");

        now = now.plus(COOLDOWN).plusSeconds(1);
        monitor.sweep();
        verify(installer, times(2)).reinstall("go");
        verify(installer, never()).uninstall(anyString());
    }

    @Test
    void repairIsAllowedAgainExactlyAtTheCooldownBoundary() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED, ToolState.FAILED, ToolState.FAILED, ToolState.RESPONDING, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        when(installer.reinstall("go")).thenReturn(installed());
        monitor.watch(DIR, delivered::add);

        monitor.sweep();
        now = now.plus(COOLDOWN).minusMillis(1);
        monitor.sweep();
        verify(installer, times(1)).reinstall("go");
        now = now.plusMillis(1);
        monitor.sweep();
        verify(installer, times(2)).reinstall("go");
    }

    @Test
    void noRepeatedRepairAfterOneThatDidNotHelpUntilTheToolWasHealthy() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        when(installer.reinstall("go")).thenReturn(installed());
        monitor.watch(DIR, delivered::add);

        monitor.sweep();
        now = now.plus(COOLDOWN).plusSeconds(1);
        monitor.sweep();
        verify(installer, times(1)).reinstall("go");

        probes(go, ToolState.RESPONDING);
        monitor.sweep();
        probes(go, ToolState.FAILED);
        now = now.plus(COOLDOWN).plusSeconds(1);
        monitor.sweep();
        verify(installer, times(2)).reinstall("go");
    }

    @Test
    void aFailedRepairKeepsTheStateFailedAndIsNotRepeated() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        when(installer.reinstall("go")).thenReturn(
                new ToolInstallService.InstallResult(ToolInstallService.Outcome.FAILED, -1, null, "offline"));
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered.get(0)).containsExactly(new DetectedTechnology(go, ToolState.FAILED));
        verify(installer, never()).uninstall(anyString());
    }

    @Test
    void aBusyInstallerSkipsTheRepairWithoutUsingUpTheCooldown() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        when(installer.reinstall("go")).thenReturn(
                new ToolInstallService.InstallResult(ToolInstallService.Outcome.BLOCKED, -1, null, "busy"), installed());
        monitor.watch(DIR, delivered::add);

        monitor.sweep();
        monitor.sweep();

        verify(installer, times(2)).reinstall("go");
    }

    @Test
    void failedAndMissingMixedKeepMissingUntouchedAndReportFailed() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED), new DetectedTechnology(rust, ToolState.MISSING));
        probes(go, ToolState.FAILED);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0)).containsExactly(new DetectedTechnology(go, ToolState.FAILED),
                new DetectedTechnology(rust, ToolState.MISSING));
        verify(probe, never()).probeFresh("rust");
    }

    @Test
    void aFailingPathCopyIsNeverRepaired() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        when(installer.runsManagedLauncher("go")).thenReturn(false);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        verify(installer, never()).reinstall(anyString());
        verify(installer, never()).uninstall(anyString());
        assertThat(delivered.get(0)).containsExactly(new DetectedTechnology(go, ToolState.FAILED));
    }

    @Test
    void noRecoveryWhileAnInstallRuns() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.FAILED);
        when(installer.isInstalling()).thenReturn(true);
        when(installer.runsManagedLauncher("go")).thenReturn(true);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(pauses).isEmpty();
        verify(installer, never()).reinstall(anyString());
        assertThat(delivered.get(0)).containsExactly(new DetectedTechnology(go, ToolState.FAILED));
    }

    @Test
    void aThrowingWatcherDoesNotStopTheOthers() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.RESPONDING);
        monitor.watch(DIR, found -> { throw new IllegalStateException("boom"); });
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered).hasSize(1);
    }

    @Test
    void aWatcherRemovedDuringTheCheckGetsNothing() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        var unsubscribe = new Runnable[1];
        when(probe.probeFresh("go")).thenAnswer(call -> {
            unsubscribe[0].run();
            return new ToolStatus("go", ToolState.RESPONDING, "", "");
        });
        unsubscribe[0] = monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered).isEmpty();
    }

    @Test
    void unsubscribeStopsNotifications() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED));
        probes(go, ToolState.RESPONDING, ToolState.INSTALLED);
        var unsubscribe = monitor.watch(DIR, delivered::add);

        monitor.sweep();
        unsubscribe.run();
        monitor.sweep();

        assertThat(delivered).hasSize(1);
    }

    @Test
    void aThrowingProbeDoesNotStopOtherTools() {
        detect(new DetectedTechnology(go, ToolState.INSTALLED), new DetectedTechnology(rust, ToolState.INSTALLED));
        when(probe.probeFresh("go")).thenThrow(new IllegalStateException("boom"));
        probes(rust, ToolState.RESPONDING);
        monitor.watch(DIR, delivered::add);

        monitor.sweep();

        assertThat(delivered).containsExactly(List.of(new DetectedTechnology(go, ToolState.INSTALLED),
                new DetectedTechnology(rust, ToolState.RESPONDING)));
    }

    @Test
    void stopShutsTheSchedulerDown() throws InterruptedException {
        monitor.begin();
        monitor.end();
        monitor.end();
    }
}
