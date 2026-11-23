package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PosixUpdateScriptTest {

    private static final String FRAGMENT = "step install /bin/true \"$1\"";

    @Test
    void scriptWaitsForTheProcessThenRunsTheFragmentAndAlwaysRelaunches() {
        var script = PosixUpdateScript.script(FRAGMENT);

        assertThat(script).startsWith("#!/bin/sh").contains("while kill -0 \"$PID\" 2>/dev/null; do").contains("kill -TERM \"$PID\"").contains("kill -KILL \"$PID\"").contains(FRAGMENT)
                .doesNotContain("@@PLATFORM_STEPS@@");
        assertThat(script.indexOf("kill -0")).isLessThan(script.indexOf("platform_update \"$@\""));
        assertThat(script.indexOf("platform_update \"$@\"")).isLessThan(script.indexOf("relaunch_linux ||"));
        assertThat(script).contains("\"$APP\" >/dev/null 2>&1 </dev/null &");
        assertThat(script).contains("case \"$SELF\" in */astrolabe-update-*) rm -rf \"$SELF\" ;; esac");
    }

    @Test
    void scriptWritesTheResultCodes() {
        var script = PosixUpdateScript.script(FRAGMENT);

        assertThat(script).contains("RESULT=OK").contains("126) if [ \"$AUTH_STEP\" = 1 ]; then RESULT=AUTH_CANCELLED").contains("RESULT=\"FAILED $STEP\"").contains("\"$UPDATES/result\"");
        assertThat(PosixUpdateScript.AUTH_CANCELLED_EXIT).isEqualTo(126);
    }

    @Test
    void logsOnlyTimestampsStepNamesAndExitCodes() {
        var script = PosixUpdateScript.script(FRAGMENT);

        assertThat(script).contains("log \"step $STEP\"").contains("log \"step $STEP exit $rc\"").contains("log \"result $RESULT\"");
    }

    @Test
    void commandPassesEveryPathAsPositionalArgumentOutsideTheScriptText() {
        var script = Path.of("tmp dir", "update.sh");
        var command = PosixUpdateScript.command(script, Path.of("up dates"), 4242, Path.of("my app", "run"), new PosixUpdateScript.Timing(60, 5, 1, 4),
                PosixUpdateScript.Notices.linux("2.4.0", "astrolabe-studio", true), List.of("pkg; rm -rf x.deb", "$(touch y)"), List.of(Path.of("left over")));

        assertThat(command).startsWith("/bin/sh", "-c").contains("4242", "pkg; rm -rf x.deb", "$(touch y)");
        assertThat(command.get(2)).doesNotContain("update.sh").doesNotContain("4242").doesNotContain("rm -rf").doesNotContain("touch");
        assertThat(command.subList(3, 5)).containsExactly("sh", "/bin/sh");
        assertThat(Path.of(command.get(5))).isAbsolute().endsWithRaw(Path.of("update.sh"));
        assertThat(command.subList(command.size() - 4, command.size() - 1)).containsExactly("2", "pkg; rm -rf x.deb", "$(touch y)");
        assertThat(command.subList(9, 13)).containsExactly("60", "5", "1", "4");
        assertThat(command.subList(13, 15)).containsExactly("2.4.0", "astrolabe-studio");
        assertThat(Path.of(command.getLast())).isAbsolute().endsWithRaw(Path.of("left over"));
    }

    @Test
    void macOsNoticesKeepTheDesktopIdEmptySoTheScriptKeepsTheOldRelaunch() {
        assertThat(PosixUpdateScript.Notices.NONE.desktopId()).isEmpty();
        assertThat(PosixUpdateScript.Notices.NONE.arguments()).allMatch(String::isEmpty);
    }

    @Test
    void passwordHintIsInTheStartTextOnlyForPackageInstalls() {
        assertThat(PosixUpdateScript.Notices.linux("2.4.0", "x", true).start()).contains("password");
        assertThat(PosixUpdateScript.Notices.linux("2.4.0", "x", false).start()).doesNotContain("password");
        assertThat(PosixUpdateScript.Notices.linux("", "x", false).start()).contains("the new version");
    }
}
