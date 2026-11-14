package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.client.McpAuthRequiredException;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.ccmanager.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.ccmanager.codemode.policy.Decision;
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ContainerRuntimeDetector.Availability;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition.Transport;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Listing, searching and executing scripts through the service. */
class CodeModeServiceTest extends CodeModeFixture {

    @Test
    void listingShowsEligibilityWithoutConnecting() {
        var servers = service.listServers(SESSION);

        assertThat(servers).extracting(ServerStatus::name).containsExactly("alpha", "beta", "gone", "pending");
        assertThat(servers.get(0).problem()).isEmpty();
        assertThat(servers.get(2).problem()).contains(ServerCandidates.NOT_FOUND);
        assertThat(servers.get(3).problem()).contains(ServerCandidates.NOT_APPROVED);
        verify(clients, never()).create(any());
    }

    @Test
    void searchConnectsLazilyOnceAndReportsUnavailableServers() {
        var first = service.searchTools(SESSION, "echo", "");
        service.searchTools(SESSION, "", "");

        assertThat(first).contains("servers.alpha.echo").contains("Unavailable servers:").contains("gone").contains("pending");
        verify(clients, times(2)).create(any());
        verify(alpha).initialize();
        verify(alpha, times(1)).listTools();
    }

    @Test
    void serverNeedingSignInIsReportedAndNotRetried() {
        org.mockito.Mockito.doThrow(new McpAuthRequiredException(401)).when(beta).initialize();

        service.searchTools(SESSION, "", "");
        var second = service.searchTools(SESSION, "", "");

        assertThat(second).contains("beta: needs sign-in");
        verify(clients, times(1)).create(argThat("beta"));
        verify(beta).close();
    }

    @Test
    void searchToolsCanBeLimitedToOneServerInAnySpelling() {
        var all = service.searchTools(SESSION, "", "");
        var one = service.searchTools(SESSION, "", "mcp__alpha__echo");

        assertThat(all).contains("echo").contains("ping");
        assertThat(one).contains("echo").doesNotContain("ping");
        assertThatThrownBy(() -> service.searchTools(SESSION, "", "nosuch")).hasMessageContaining("Unknown server 'nosuch'");
    }

    @Test
    void executeParsesJsonTextAndAuditsWithoutArguments() {
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"n\\\":1}\"}]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{\"secret\":\"x\"}"), false));

        var result = service.execute(SESSION, "code");

        assertThat(result.output()).isEqualTo("{\"n\":1}");
        verify(audit).record(any(), org.mockito.ArgumentMatchers.eq("alpha"), org.mockito.ArgumentMatchers.eq("echo"),
                org.mockito.ArgumentMatchers.eq(Decision.Kind.ALLOWED_AUTO), org.mockito.ArgumentMatchers.eq(CodeModeAudit.Status.OK), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unavailableBackendFailsWithTheReasonAndNeverFallsBack() {
        when(isolation.get()).thenReturn(IsolationBackend.DOCKER);
        when(detector.availability(IsolationBackend.DOCKER)).thenReturn(new Availability(false, "docker is not installed or not on PATH"));

        assertThatThrownBy(() -> service.execute(SESSION, "code")).isInstanceOf(IllegalStateException.class)
                .hasMessage("docker is not installed or not on PATH");
        verify(sandboxes, never()).forBackend(any());
    }

    @Test
    void missingContainerImageFailsClosedToo() {
        when(isolation.get()).thenReturn(IsolationBackend.PODMAN);
        when(detector.availability(IsolationBackend.PODMAN)).thenReturn(new Availability(true, ""));
        when(detector.imagePresent(IsolationBackend.PODMAN, "node:22-alpine")).thenReturn(false);

        assertThatThrownBy(() -> service.execute(SESSION, "code")).hasMessageContaining("node:22-alpine");
        verify(sandboxes, never()).forBackend(any());
    }

    @Test
    void containerBackendRunsWhenAvailableAndImagePresent() {
        when(isolation.get()).thenReturn(IsolationBackend.DOCKER);
        when(detector.availability(IsolationBackend.DOCKER)).thenReturn(new Availability(true, ""));
        when(detector.imagePresent(IsolationBackend.DOCKER, "node:22-alpine")).thenReturn(true);
        script.set((code, bridge, session) -> ScriptResult.ok("done", false));

        assertThat(service.execute(SESSION, "code").output()).isEqualTo("done");
        verify(sandboxes).forBackend(IsolationBackend.DOCKER);
    }

    @Test
    void onlyOneScriptRunsPerSession() {
        var nested = new AtomicReference<Throwable>();
        script.set((code, bridge, session) -> {
            try {
                service.execute(SESSION, "inner");
            } catch (IllegalStateException e) {
                nested.set(e);
            }
            return ScriptResult.ok("outer", false);
        });

        assertThat(service.execute(SESSION, "outer").output()).isEqualTo("outer");
        assertThat(nested.get()).hasMessageContaining("already running");
        script.set((code, bridge, session) -> ScriptResult.ok("again", false));
        assertThat(service.execute(SESSION, "code").output()).isEqualTo("again");
    }

    @Test
    void projectWithoutCodeModeIsRefused() {
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of()));

        assertThatThrownBy(() -> service.execute(SESSION, "code")).hasMessage(CodeModeService.NOT_ENABLED);
        assertThatThrownBy(() -> service.searchTools(SESSION, "", "")).hasMessage(CodeModeService.NOT_ENABLED);
    }

    @Test
    void cancelArrivingAfterTheScriptFinishedDoesNotInterruptTheRequestThread() {
        script.set((code, bridge, session) -> ScriptResult.ok("done", false));
        service.execute(SESSION, "code");
        var cancel = ArgumentCaptor.forClass(Runnable.class);
        verify(executions).register(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any(java.util.function.LongSupplier.class), cancel.capture(),
                org.mockito.ArgumentMatchers.any());

        cancel.getValue().run();

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void containerCheckIsReusedForTheTtlAndRepeatedAfterAFailure() {
        when(isolation.get()).thenReturn(IsolationBackend.DOCKER);
        when(detector.availability(IsolationBackend.DOCKER)).thenReturn(new Availability(false, "down"), new Availability(true, ""));
        when(detector.imagePresent(IsolationBackend.DOCKER, "node:22-alpine")).thenReturn(true);
        script.set((code, bridge, session) -> ScriptResult.ok("done", false));

        assertThatThrownBy(() -> service.execute(SESSION, "code")).hasMessage("down");
        service.execute(SESSION, "code");
        service.execute(SESSION, "code");

        verify(detector, times(2)).availability(IsolationBackend.DOCKER);
        verify(detector, times(1)).imagePresent(IsolationBackend.DOCKER, "node:22-alpine");
    }

    @Test
    void serversAreReadFromTheSessionsWorkingDirectory() throws Exception {
        var worktree = Files.createDirectories(dataDir.resolve("worktree"));
        when(modes.directoryOf("s1")).thenReturn(Optional.of(worktree));
        when(reader.definitions(worktree)).thenReturn(List.of(definition("alpha", ExtensionScope.PROJECT, Transport.STDIO)));
        when(mcp.servers(worktree)).thenReturn(List.of(new McpServerConfig("alpha", ExtensionScope.PROJECT, "stdio", "x", McpApproval.APPROVED)));

        var servers = service.listServers(SESSION);

        assertThat(servers.get(0).problem()).isEmpty();
        assertThat(servers.get(1).problem()).contains(ServerCandidates.NOT_FOUND);
        verify(reader, never()).definitions(dataDir);
    }

    private void scriptWaitingFor(CountDownLatch release, CountDownLatch running, String result) {
        script.set((code, bridge, session) -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                return ScriptResult.failed("was stopped");
            }
            return ScriptResult.ok(result, false);
        });
    }

    @Test
    void searchWithAnUnknownServerFilterFailsBeforeStartingAnyServer() {
        assertThatThrownBy(() -> service.searchTools(SESSION, "", "alhpa")).hasMessageContaining("Unknown server 'alhpa'");

        verify(clients, never()).create(any());
    }

    @Test
    void searchLimitedToOneServerStartsOnlyThatServer() {
        service.searchTools(SESSION, "", "alpha");

        verify(clients, times(1)).create(argThat("alpha"));
        verify(clients, never()).create(argThat("beta"));
    }

    @Test
    void cancelArrivingBeforeTheRunIsRegisteredStopsItBeforeTheScriptStarts() {
        var request = new RunCancellation();
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        script.set((code, bridge, session) -> {
            started.set(true);
            return ScriptResult.ok("ran", false);
        });
        when(isolation.get()).thenReturn(IsolationBackend.DOCKER);
        when(detector.availability(IsolationBackend.DOCKER)).thenAnswer(call -> {
            request.cancel();
            return new Availability(true, "");
        });
        when(detector.imagePresent(IsolationBackend.DOCKER, "node:22-alpine")).thenReturn(true);

        var result = service.execute(SESSION, "code", request);

        assertThat(result.error()).hasValue(ScriptBridge.CANCELLED);
        assertThat(started).isFalse();
        assertThat(executions.isRunning("s1")).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void cancelOfARefusedSecondExecuteDoesNotStopTheScriptThatIsRunning() throws Exception {
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        scriptWaitingFor(release, running, "first finished");
        var outcome = new AtomicReference<ScriptResult>();
        var first = Thread.ofPlatform().start(() -> outcome.set(service.execute(SESSION, "first")));
        assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second = new RunCancellation();

        assertThatThrownBy(() -> service.execute(SESSION, "second", second)).hasMessageContaining("already running");
        second.cancel();
        release.countDown();
        first.join(5_000);

        assertThat(outcome.get().output()).isEqualTo("first finished");
    }

    @Test
    void lateCancelOfAFinishedRequestDoesNotStopTheNextRun() throws Exception {
        var finished = new RunCancellation();
        script.set((code, bridge, session) -> ScriptResult.ok("one", false));
        service.execute(SESSION, "one", finished);
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        scriptWaitingFor(release, running, "two");
        var outcome = new AtomicReference<ScriptResult>();
        var next = Thread.ofPlatform().start(() -> outcome.set(service.execute(SESSION, "two")));
        assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        finished.cancel();
        release.countDown();
        next.join(5_000);

        assertThat(outcome.get().output()).isEqualTo("two");
    }
}
