package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShimLaunchTest {

    private static final Path DIR = Path.of("tools");

    @Test
    void windowsCmdAndBatShimsRunThroughCmdExe() {
        var cmd = DIR.resolve("ngserver.cmd");
        var bat = DIR.resolve("x.BAT");

        assertThat(ShimLaunch.command(true, cmd, List.of("--version"))).containsExactly("cmd.exe", "/c", cmd.toString(), "--version");
        assertThat(ShimLaunch.command(true, bat, List.of())).containsExactly("cmd.exe", "/c", bat.toString());
    }

    @Test
    void exeIsNotWrapped() {
        var exe = DIR.resolve("go.exe");

        assertThat(ShimLaunch.command(true, exe, List.of("version"))).containsExactly(exe.toString(), "version");
    }

    @Test
    void nonWindowsNeverWraps() {
        var shim = DIR.resolve("tool.cmd");

        assertThat(ShimLaunch.command(false, shim, List.of("a"))).containsExactly(shim.toString(), "a");
    }

    @Test
    void unsafePathOrArgumentOfAShimIsRejectedWithNeutralMessage() {
        assertThatThrownBy(() -> ShimLaunch.command(true, Path.of("a&b").resolve("tool.cmd"), List.of()))
                .isInstanceOf(ShimLaunch.UnsafeLaunchException.class)
                .hasMessage("unsafe path for a .cmd launcher");
        assertThatThrownBy(() -> ShimLaunch.command(true, DIR.resolve("tool.cmd"), List.of("a|b")))
                .isInstanceOf(ShimLaunch.UnsafeLaunchException.class);
    }

    @Test
    void metacharactersAreFineForNonBatchLaunchers() {
        var exe = Path.of("a&b").resolve("tool.exe");

        assertThat(ShimLaunch.command(true, exe, List.of("a|b"))).containsExactly(exe.toString(), "a|b");
    }
}
