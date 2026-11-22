package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MsiUpdaterTest {

    @Test
    void commandPassesPlainArgumentsForTheJdkToQuote() {
        var command = MsiUpdater.command(Path.of("a b", "l.vbs"), Path.of("a b", "u.cmd"), Path.of("a&b", "app.msi"), "x y/app.exe");

        assertThat(command).hasSize(7).startsWith("wscript.exe", "//B").doesNotContain("cmd.exe").noneMatch(a -> a.contains("\""));
        assertThat(Path.of(command.get(6))).isAbsolute();
    }

    @Test
    void needsNoAuthenticationAndReturnsTheDownloadUnchanged() {
        var msi = Path.of("a.msi");

        assertThat(new MsiUpdater().needsAuthentication()).isFalse();
        assertThat(new MsiUpdater().prepare(msi, "ab".repeat(32), null)).isEqualTo(msi);
    }

    @Test
    void scriptWaitsForTheProcessThenInstallsAndRestarts() {
        var script = MsiUpdater.script(4242);

        assertThat(script).contains("PID eq 4242").contains("find \"4242\"").contains("msiexec /i \"%~1\" /passive").contains("start \"\" \"%~2\"").contains("update.log").contains("del \"%~f0\"").contains("& exit");
    }
}
