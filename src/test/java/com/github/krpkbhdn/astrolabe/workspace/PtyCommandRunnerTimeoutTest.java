package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PtyCommandRunnerTimeoutTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** Process start-up on a loaded CI machine; still far below the old ten second wait. */
    private static final Duration SLACK = Duration.ofSeconds(3);

    @Test
    void aCommandThatOutlivesTheTimeoutIsKilledAndReported() throws Exception {
        var command = WINDOWS ? List.of("cmd", "/c", "echo started& ping -n 60 127.0.0.1 >nul") : List.of("sh", "-c", "echo started; sleep 60");
        var started = System.nanoTime();

        var result = PtyCommandRunner.run(command, null, Duration.ofSeconds(3), Map.of(), line -> { });

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(25));
    }

    @Test
    void aSilentCommandReturnsAfterTheQuietWindowNotTheOldTenSecondWait() throws Exception {
        var command = WINDOWS ? List.of("cmd", "/c", "exit 0") : List.of("sh", "-c", "exit 0");
        var started = System.nanoTime();

        var result = PtyCommandRunner.run(command, null, Duration.ofSeconds(10), Map.of(), line -> { });

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(PtyCommandRunner.READER_QUIET.plus(SLACK));
    }

    @Test
    void outputArrivingAfterAPauseShorterThanTheQuietWindowIsStillReturned() throws Exception {
        var command = WINDOWS
                ? List.of("cmd", "/c", "echo first-part& ping -n 2 127.0.0.1 >nul& echo second-part")
                : List.of("sh", "-c", "echo first-part; sleep 0.5; echo second-part");

        var result = PtyCommandRunner.run(command, null, Duration.ofSeconds(10), Map.of(), line -> { });

        assertThat(result.stdout()).contains("first-part").contains("second-part");
    }

    @Test
    void outputPrintedBeforeTheExitIsReturned() throws Exception {
        var command = WINDOWS ? List.of("cmd", "/c", "echo hello-pty") : List.of("sh", "-c", "echo hello-pty");

        var result = PtyCommandRunner.run(command, null, Duration.ofSeconds(10), Map.of(), line -> { });

        assertThat(result.stdout()).contains("hello-pty");
    }
}
