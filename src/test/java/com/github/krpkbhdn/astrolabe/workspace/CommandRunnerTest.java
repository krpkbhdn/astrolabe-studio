package com.github.krpkbhdn.astrolabe.workspace;

import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void outputUnderTheLimitIsIntactAndExitCodeIsReported() throws IOException {
        PtyAvailability.assumePtyDeliversOutput();
        var result = CommandRunner.run(shell("echo hello & exit 3"), null, TIMEOUT);

        assertThat(result.stdout().strip()).isEqualTo("hello");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void outputOverTheLimitIsTruncated() throws IOException {
        PtyAvailability.assumePtyDeliversOutput();
        var result = CommandRunner.run(shell("echo 0123456789012345678901234567890123456789"), null, TIMEOUT, 10);

        assertThat(result.stdout()).isEqualTo("0123456789");
        assertThat(result.exitCode()).isZero();
        assertThat(result.truncated()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void aCommandOverTheTimeoutIsKilledAndReported() throws IOException {
        var result = CommandRunner.run(shell("ping -n 30 127.0.0.1 > nul || sleep 30"), null, Duration.ofMillis(200));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    private static List<String> shell(String line) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd", "/c", line)
                : List.of("sh", "-c", line.replace(" & ", "; ").replace("ping -n 30 127.0.0.1 > nul || ", ""));
    }

    @Test
    void extraEnvironmentReachesTheChildAndTheInheritedOneIsKept() throws IOException {
        PtyAvailability.assumePtyDeliversOutput();
        var windows = System.getProperty("os.name").toLowerCase().contains("win");
        var command = windows
                ? List.of("cmd", "/c", "echo %ASTROLABE_TEST_VAR%;%SystemRoot%")
                : List.of("sh", "-c", "echo $ASTROLABE_TEST_VAR\\;$HOME");
        var inherited = windows ? System.getenv("SystemRoot") : System.getenv("HOME");

        var result = CommandRunner.run(command, null, TIMEOUT, Map.of("ASTROLABE_TEST_VAR", "astrolabe-value"));

        assertThat(result.stdout()).contains("astrolabe-value;").contains(inherited);
    }
}
