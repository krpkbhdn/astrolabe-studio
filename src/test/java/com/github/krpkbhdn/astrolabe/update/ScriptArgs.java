package com.github.krpkbhdn.astrolabe.update;

import java.util.ArrayList;
import java.util.List;

/** Builds the positional arguments of a generated {@code update.sh} for tests that run it directly (no settle wait, one second alive check). */
final class ScriptArgs {

    static final PosixUpdateScript.Timing TIMING = new PosixUpdateScript.Timing(60, 5, 0, 1);
    /** The shell form of the settle, alive-check and empty notice arguments, for tests that build a {@code sh -c} string. */
    static final String NEUTRAL_SHELL = "0 1" + " ''".repeat(PosixUpdateScript.Notices.NONE.arguments().size());

    private ScriptArgs() {
    }

    static List<String> command(String script, String updates, String pid, String app, PosixUpdateScript.Timing timing, PosixUpdateScript.Notices notices,
            List<String> extra, String... cleanup) {
        var command = new ArrayList<>(List.of("sh", script, updates, pid, app, Long.toString(timing.waitSeconds()), Long.toString(timing.graceSeconds()),
                Long.toString(timing.settleSeconds()), Long.toString(timing.aliveSeconds())));
        command.addAll(notices.arguments());
        command.add(Integer.toString(extra.size()));
        command.addAll(extra);
        command.addAll(List.of(cleanup));
        return command;
    }

    static List<String> command(String script, String updates, String pid, String app, List<String> extra, String... cleanup) {
        return command(script, updates, pid, app, TIMING, PosixUpdateScript.Notices.NONE, extra, cleanup);
    }
}
