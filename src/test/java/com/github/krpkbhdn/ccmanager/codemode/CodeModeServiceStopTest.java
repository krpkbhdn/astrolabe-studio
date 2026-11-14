package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.policy.Decision;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Timeouts and stops of a running script: partial output, approval time and late answers. */
class CodeModeServiceStopTest extends CodeModeFixture {

    @Test
    void partialOutputSurvivesAStop() throws Exception {
        var started = new CountDownLatch(1);
        script.set((code, bridge, session) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                return ScriptResult.failed("interrupted", "printed so far");
            }
            return ScriptResult.ok("late", false);
        });
        var outcome = new AtomicReference<ScriptResult>();
        var runner = Thread.ofPlatform().start(() -> outcome.set(service.execute(SESSION, "code")));
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        executions.cancel("s1");
        runner.join(5_000);

        assertThat(outcome.get().output()).isEqualTo("printed so far");
        assertThat(outcome.get().error()).hasValueSatisfying(error -> assertThat(error).contains("cancelled"));
    }

    @Test
    void timeSpentWaitingForTheUserDoesNotCountAgainstTheScriptTimeout() throws Exception {
        var asking = new CountDownLatch(1);
        var answer = new CountDownLatch(1);
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[]}"));
        when(gate.decide(any(), any(), any(), any())).thenAnswer(call -> {
            asking.countDown();
            answer.await();
            return Decision.USER;
        });
        scriptCallingAlphaAndReportingErrors();
        var outcome = new AtomicReference<ScriptResult>();
        var runner = Thread.ofPlatform().start(() -> outcome.set(serviceWith(scriptTimeout(Duration.ofMillis(100))).execute(SESSION, "code")));
        assertThat(asking.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        sleep(400);
        answer.countDown();
        runner.join(10_000);

        assertThat(outcome.get().error()).isEmpty();
        verify(alpha).callTool(any(), any());
    }

    @Test
    void scriptRunningPastItsTimeoutWithoutAskingIsStoppedAndRunsNoMoreTools() {
        script.set((code, bridge, session) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                return ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false);
            } catch (RuntimeException e) {
                return ScriptResult.failed(e.getMessage());
            }
        });

        var result = serviceWith(scriptTimeout(Duration.ofMillis(200))).execute(SESSION, "code");

        assertThat(result.error()).hasValueSatisfying(error -> assertThat(error).contains("timed out"));
        verify(alpha, never()).callTool(any(), any());
    }

    @Test
    void answerArrivingAfterTheScriptWasStoppedRunsNoTool() throws Exception {
        var asking = new CountDownLatch(1);
        var answer = new CountDownLatch(1);
        when(gate.decide(any(), any(), any(), any())).thenAnswer(call -> {
            asking.countDown();
            try {
                answer.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Decision.USER;
        });
        scriptCallingAlphaAndReportingErrors();
        var running = serviceWith(scriptTimeout(Duration.ofSeconds(30)));
        var outcome = new AtomicReference<ScriptResult>();
        var runner = Thread.ofPlatform().start(() -> outcome.set(running.execute(SESSION, "code")));
        assertThat(asking.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        executions.cancel("s1");
        runner.join(5_000);
        answer.countDown();

        assertThat(outcome.get().error()).hasValueSatisfying(error -> assertThat(error).contains("cancelled"));
        verify(alpha, never()).callTool(any(), any());
    }
}
