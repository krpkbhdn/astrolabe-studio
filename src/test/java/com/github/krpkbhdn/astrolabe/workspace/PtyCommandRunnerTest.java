package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PtyCommandRunnerTest {

    @Test
    void readReportsTheRenderedTextSoFar() {
        var raw = "\u001B[?25l\u001B[1;32mWaiting\u001B[0m 10%\rWaiting 60%\r\n";
        var reported = new java.util.ArrayList<String>();

        PtyCommandRunner.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), new StringBuilder(), reported::add);

        assertThat(reported.getLast()).isEqualTo("Waiting 60%\n");
    }

    @Test
    void extraEnvironmentReplacesInheritedVariableIgnoringCase() {
        var merged = PtyCommandRunner.mergedEnvironment(Map.of("Path", "old", "HOME", "h"), Map.of("PATH", "new"));

        assertThat(merged).containsOnly(Map.entry("PATH", "new"), Map.entry("HOME", "h"));
    }

    @Test
    void outputPastTheCapIsDroppedAndMultiByteCharactersSplitAcrossReadsSurvive() {
        var text = "é".repeat(PtyCommandRunner.MAX_RAW_CHARS) + "tail";
        var output = new StringBuilder();

        PtyCommandRunner.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), output, ignored -> { });

        assertThat(output.toString()).isEqualTo("é".repeat(PtyCommandRunner.MAX_RAW_CHARS));
    }

    @Test
    void runPassesTheExtraEnvironmentToTheChild() throws java.io.IOException {
        var windows = System.getProperty("os.name").toLowerCase().contains("win");
        var command = windows ? java.util.List.of("cmd", "/c", "echo %ASTROLABE_TEST_VAR%")
                : java.util.List.of("sh", "-c", "echo $ASTROLABE_TEST_VAR");

        var result = PtyCommandRunner.run(command, null, java.time.Duration.ofSeconds(20), Map.of("ASTROLABE_TEST_VAR", "astrolabe-value"), line -> { });

        assertThat(result.stdout()).contains("astrolabe-value");
    }
}
