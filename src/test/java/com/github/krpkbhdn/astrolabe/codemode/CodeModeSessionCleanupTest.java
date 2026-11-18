package com.github.krpkbhdn.astrolabe.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationSettingsService;
import com.github.krpkbhdn.astrolabe.codemode.policy.ExecutionRegistry;
import com.github.krpkbhdn.astrolabe.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector.Availability;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class CodeModeSessionCleanupTest {

    private static final String INSTANCE = "0123456789abcdef";

    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final ProjectCodeModeService modes = mock(ProjectCodeModeService.class);
    private final ServerConnections connections = mock(ServerConnections.class);
    private final ToolCallGate gate = mock(ToolCallGate.class);
    private final IsolationSettingsService isolation = mock(IsolationSettingsService.class);
    private final ContainerRuntimeDetector detector = mock(ContainerRuntimeDetector.class);
    private final CodeModeSandboxes sandboxes = mock(CodeModeSandboxes.class);
    private static final java.util.function.LongSupplier NEVER = () -> Long.MAX_VALUE;
    private final ExecutionRegistry executions = new ExecutionRegistry();
    private final AtomicInteger cancelled = new AtomicInteger();
    private Path dataDir;
    private Runnable listener;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        dataDir = temp;
        when(sandboxes.instanceId()).thenReturn(INSTANCE);
        when(isolation.get()).thenReturn(IsolationBackend.GRAALJS);
        executions.register("s1", NEVER, cancelled::incrementAndGet);
    }

    private CodeModeSessionCleanup cleanup(Duration idleCancelDelay) {
        var properties = TestProperties.of(Duration.ofSeconds(30), 1, 1, 1, Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25))
                .toBuilder().idleCancelDelay(idleCancelDelay).build();
        var created = new CodeModeSessionCleanup(sessions, modes, connections, gate, executions, isolation, detector, sandboxes, properties,
                new AppProperties(dataDir));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(sessions).subscribe(callback.capture());
        listener = callback.getValue();
        return created;
    }

    private void status(String sessionId, AgentStatus status) {
        var snapshot = mock(SessionSnapshot.class);
        when(snapshot.status()).thenReturn(status);
        when(sessions.find(sessionId)).thenReturn(Optional.of(snapshot));
    }

    @Test
    void sessionNeedingAnActionKeepsItsScriptAlive() {
        var cleanup = cleanup(Duration.ofHours(1));
        status("s1", AgentStatus.NEEDS_ACTION);

        listener.run();
        cleanup.confirmIdle("s1");

        assertThat(cancelled).hasValue(0);
    }

    @Test
    void sessionTheRegistryDoesNotKnowYetIsNeitherCancelledNorForgotten() {
        var cleanup = cleanup(Duration.ofHours(1));
        when(modes.rememberedSessionIds()).thenReturn(Set.of("s1"));
        when(sessions.find("s1")).thenReturn(Optional.empty());

        listener.run();
        cleanup.confirmIdle("s1");

        assertThat(cancelled).hasValue(0);
        verify(connections, never()).closeSession(anyString());
    }

    @Test
    void idleReadingIsConfirmedBeforeTheScriptIsCancelled() {
        var cleanup = cleanup(Duration.ofHours(1));
        status("s1", AgentStatus.IDLE);

        listener.run();
        assertThat(cancelled).hasValue(0);
        cleanup.confirmIdle("s1");

        assertThat(cancelled).hasValue(1);
    }

    @Test
    void laggingIdleReadingThatTurnsIntoWorkingDoesNotCancel() {
        var cleanup = cleanup(Duration.ofHours(1));
        status("s1", AgentStatus.IDLE);

        listener.run();
        status("s1", AgentStatus.WORKING);
        cleanup.confirmIdle("s1");

        assertThat(cancelled).hasValue(0);
    }

    @Test
    void idleSessionLosesItsScriptAfterTheConfiguredDelay() throws Exception {
        var gone = new CountDownLatch(1);
        executions.register("s2", NEVER, gone::countDown);
        cleanup(Duration.ofMillis(20));
        status("s2", AgentStatus.IDLE);

        listener.run();

        assertThat(gone.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void offlineSessionLosesItsScriptAtOnce() {
        cleanup(Duration.ofHours(1));
        status("s1", AgentStatus.OFFLINE);

        listener.run();

        assertThat(cancelled.get()).isPositive();
    }

    @Test
    void staleContainersAreSweptOnlyForTheSavedContainerBackend() {
        when(isolation.get()).thenReturn(IsolationBackend.PODMAN);
        when(detector.availability(IsolationBackend.PODMAN)).thenReturn(new Availability(true, ""));
        var cleanup = cleanup(Duration.ofHours(1));

        cleanup.sweepStaleContainers();

        verify(detector, timeout(5_000)).killStaleContainers(IsolationBackend.PODMAN, INSTANCE);
        verify(detector, never()).availability(IsolationBackend.DOCKER);
    }

    @Test
    void noContainerProbeAtAllWhileGraalJsIsTheBackend() {
        var cleanup = cleanup(Duration.ofHours(1));

        cleanup.sweepStaleContainers();

        verify(detector, never()).availability(any());
    }

    @Test
    void containerSweepFailureNeverFailsStart() {
        var cleanup = cleanup(Duration.ofHours(1));
        when(isolation.get()).thenThrow(new IllegalStateException("db"));

        cleanup.sweepStaleContainers();

        doReturn(IsolationBackend.DOCKER).when(isolation).get();
        when(detector.availability(IsolationBackend.DOCKER)).thenThrow(new IllegalStateException("boom"));
        cleanup.sweepStaleContainers();
        verify(detector, timeout(5_000)).availability(IsolationBackend.DOCKER);
        verify(detector, never()).killStaleContainers(any(), anyString());
    }

    @Test
    void leftoverImageFoldersOfAnEarlierRunAreRemovedAtStart() throws Exception {
        var old = Files.createDirectories(dataDir.resolve("code-mode").resolve("old-session"));
        Files.write(old.resolve("img-1.png"), new byte[] {1});
        var cleanup = cleanup(Duration.ofHours(1));

        cleanup.sweepLeftoverImages();

        assertThat(old).doesNotExist();
        assertThat(dataDir.resolve("code-mode")).isDirectory();
    }
}
