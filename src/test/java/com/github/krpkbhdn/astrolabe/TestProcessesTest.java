package com.github.krpkbhdn.astrolabe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestProcessesTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static Process start(String windowsCommand, String shCommand) throws Exception {
        return new ProcessBuilder(WINDOWS ? List.of("cmd", "/c", windowsCommand) : List.of("sh", "-c", shCommand)).start();
    }

    @Test
    void runReturnsExitCodeAndBothStreams() throws Exception {
        var drained = TestProcesses.run(start("echo out& echo err 1>&2& exit 3", "echo out; echo err >&2; exit 3"));

        assertThat(drained.exitCode()).isEqualTo(3);
        assertThat(drained.stdout()).contains("out");
        assertThat(drained.stderr()).contains("err");
    }

    @Test
    void gitRunsWithoutPromptsAndExitsCleanly() throws Exception {
        var builder = TestProcesses.git("--version");

        assertThat(builder.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0");
        var drained = TestProcesses.run(builder.start());
        assertThat(drained.exitCode()).isZero();
        assertThat(drained.stdout()).startsWith("git version");
    }

    @Test
    void runFailsFastAndKillsAChildThatNeverExits() throws Exception {
        var process = start("ping -n 60 127.0.0.1 >nul", "sleep 60");

        assertThatThrownBy(() -> TestProcesses.run(process, Duration.ofMillis(200))).isInstanceOf(AssertionError.class).hasMessageContaining("did not exit");

        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
