package com.github.krpkbhdn.ccmanager.codemode;

import java.util.Optional;

/** Outcome of one script: the returned/printed output, an error message if it failed, and whether the output was cut at the limit. */
public record ScriptResult(String output, Optional<String> error, boolean truncated) {

    public static ScriptResult ok(String output, boolean truncated) {
        return new ScriptResult(output, Optional.empty(), truncated);
    }

    public static ScriptResult failed(String error) {
        return failed(error, "");
    }

    /** A failure that keeps what the script printed before it. */
    public static ScriptResult failed(String error, String output) {
        return new ScriptResult(output, Optional.of(error), false);
    }
}
