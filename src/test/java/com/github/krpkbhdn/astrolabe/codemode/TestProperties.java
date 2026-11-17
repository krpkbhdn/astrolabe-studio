package com.github.krpkbhdn.astrolabe.codemode;

import java.time.Duration;

/** Code Mode tunables for tests: the first ten limits are given, the rest take the production defaults. */
public final class TestProperties {

    private TestProperties() {
    }

    public static CodeModeProperties of(Duration timeout, long statementLimit, int maxOutputChars, int searchLimit, Duration idleClientClose, String image,
            String containerMemory, double containerCpus, Duration auditRetention, Duration toolCallTimeout) {
        return new CodeModeProperties(timeout, statementLimit, maxOutputChars, searchLimit, idleClientClose, image, containerMemory, containerCpus,
                auditRetention, toolCallTimeout, Duration.ofHours(1), Duration.ofSeconds(30), 50, 100L * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(2));
    }
}
