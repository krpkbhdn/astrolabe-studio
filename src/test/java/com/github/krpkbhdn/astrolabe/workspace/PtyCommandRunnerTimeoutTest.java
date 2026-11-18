package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PtyCommandRunnerTimeoutTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @Test
    void aCommandThatOutlivesTheTimeoutIsKilledAndReported() throws Exception {
        var command = WINDOWS ? List.of("cmd", "/c", "echo started& ping -n 60 127.0.0.1 >nul") : List.of("sh", "-c", "echo started; sleep 60");
        var started = System.nanoTime();

        var result = PtyCommandRunner.run(command, null, Duration.ofSeconds(3), Map.of(), line -> { });

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(25));
    }
}
