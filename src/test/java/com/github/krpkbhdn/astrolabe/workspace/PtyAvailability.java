package com.github.krpkbhdn.astrolabe.workspace;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/** Skips tests that need real child output when the host's PTY (e.g. ConPTY on a CI runner) delivers none. */
public final class PtyAvailability {

    private static final String PROBE = "probe";
    private static final boolean DELIVERS_OUTPUT = probe();

    private PtyAvailability() {
    }

    public static void assumePtyDeliversOutput() {
        Assumptions.assumeTrue(DELIVERS_OUTPUT, "the PTY of this host returned no output for a trivial command");
    }

    private static boolean probe() {
        var windows = System.getProperty("os.name").toLowerCase().contains("win");
        var command = windows ? List.of("cmd", "/c", "echo " + PROBE) : List.of("sh", "-c", "echo " + PROBE);
        try {
            return PtyCommandRunner.run(command, null, Duration.ofSeconds(20), Map.of(), line -> { }).stdout().contains(PROBE);
        } catch (IOException e) {
            return false;
        }
    }
}
