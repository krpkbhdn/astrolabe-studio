package com.github.krpkbhdn.astrolabe.codemode.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Timeouts, stops and cancels against a real script and server: the session must always recover. */
class CodeModeRecoveryTest extends EndToEndFixture {

    private static final long HEALTHY_SERVER = -1;
    private static final Duration CALL_LIMIT = Duration.ofSeconds(1);

    @BeforeEach
    void shortCallLimit() throws Exception {
        wire(Duration.ofSeconds(20), CALL_LIMIT);
    }

    private long pid() {
        return Long.parseLong(text(execute("return await servers.fake.pid({});")));
    }

    private void assertSessionRecovered(long stuckServerPid) throws Exception {
        assertThat(executions.isRunning("s1")).isFalse();
        var next = execute("return await servers.fake.pid({});");
        assertThat(next.path("isError").asBoolean()).as(text(next)).isFalse();
        var healthy = stuckServerPid == HEALTHY_SERVER;
        if (!healthy) {
            assertThat(Long.parseLong(text(next))).isNotEqualTo(stuckServerPid);
        }
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && ((!healthy && ProcessHandle.of(stuckServerPid).map(ProcessHandle::isAlive).orElse(false)) || runWorkers() > 0)) {
            Thread.sleep(5);
        }
        if (healthy) {
            assertThat(runWorkers()).as("code-mode worker threads").isZero();
            return;
        }
        assertThat(ProcessHandle.of(stuckServerPid).map(ProcessHandle::isAlive).orElse(false)).as("old server process").isFalse();
        assertThat(runWorkers()).as("code-mode worker threads").isZero();
    }

    private static long runWorkers() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("code-mode-run") || thread.getName().startsWith("code-mode-script")).count();
    }

    @Test
    void callSlowerThanTheCallLimitNamesTheLimitResetsTheServerAndTheSessionRecovers() throws Exception {
        var stuck = pid();

        var result = execute("return await servers.fake.sleep({ms: 30000});");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("timed out after 1 s").contains("astrolabe.code-mode.tool-call-timeout");
        assertSessionRecovered(stuck);
    }

    @Test
    void callThatNeverAnswersAndALateAnswerAfterTheResetBothRecover() throws Exception {
        var stuck = pid();
        var hung = execute("return await servers.fake.hang({});");
        assertThat(text(hung)).contains("tool-call-timeout");
        assertSessionRecovered(stuck);

        var stuckAgain = pid();
        execute("return await servers.fake.sleep({ms: 1500});");
        assertSessionRecovered(stuckAgain);
    }

    @Test
    void scriptOverItsTimeLimitNamesTheLimitAndTheSessionRecovers() throws Exception {
        wire(Duration.ofSeconds(1), Duration.ofSeconds(1));

        var result = execute("for (;;) { await servers.fake.add({a: 1, b: 1}); }");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("timed out after 1 s").contains("astrolabe.code-mode.timeout");
        assertSessionRecovered(HEALTHY_SERVER);
    }

    @Test
    void stopDuringABlockedCallReturnsAtOnceResetsTheServerAndReleasesTheSession() throws Exception {
        var stuck = pid();
        var outcome = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var runner = Thread.ofPlatform().start(() -> outcome.set(execute("return await servers.fake.hang({});")));
        eventually(() -> executions.isRunning("s1") && serverIsHanging(), "the call to block");

        executions.cancel("s1");
        runner.join(8_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(text(outcome.get())).contains("cancelled");
        assertSessionRecovered(stuck);
    }

    @Test
    void cancelledNotificationEndsTheBlockedScriptOfThatSessionOnlyAndTheNextExecuteWorks() throws Exception {
        var stuck = pid();
        var outcome = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var runner = executeInBackground(7, "return await servers.fake.hang({});", outcome, this::serverIsHanging);

        cancelRequest("s2", 7);
        assertThat(runner.isAlive()).isTrue();
        assertThat(executions.isRunning("s1")).isTrue();
        cancelRequest("s1", 7);
        runner.join(8_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(text(outcome.get())).contains("cancelled");
        assertSessionRecovered(stuck);
    }

    @Test
    void newExecuteIsRefusedWithTheOpenApprovalAndDoesNotCancelThePendingScriptWhichACancelThenEnds() throws Exception {
        var outcome = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var runner = executeInBackground(8, "return await servers.fake.write_file({path: 'x'});", outcome, () -> !proposals.pending().isEmpty());

        var refused = execute("return 1");

        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).isEqualTo("A script is waiting for your approval of fake.write_file in astrolabe: answer or deny the dialog, then retry");
        assertThat(runner.isAlive()).isTrue();
        cancelRequest("s1", 8);
        runner.join(8_000);
        assertThat(runner.isAlive()).isFalse();
        eventually(() -> proposals.pending().isEmpty(), "the open question to be withdrawn");
        eventually(() -> runWorkers() == 0, "the worker threads to end");
        assertSessionRecovered(HEALTHY_SERVER);
    }

    @Test
    void newScriptReplacesAnOverdueStuckRegistrationButNotALiveOne() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var live = executions.register("s1", () -> System.nanoTime() + 60_000_000_000L, () -> { });
        var busy = execute("return 1");
        assertThat(busy.path("isError").asBoolean()).isTrue();
        assertThat(text(busy)).contains("already running").contains("limit 60 s").contains("Stop script");

        live.close();
        executions.register("s1", () -> System.nanoTime() - 3_600_000_000_000L, () -> cancelled.set(true));
        var healed = execute("return 1");

        assertThat(text(healed)).isEqualTo("1");
        assertThat(cancelled).isTrue();
    }
}
