package com.github.krpkbhdn.astrolabe.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LinuxWindowClassTest {
    @Test
    fun `install script StartupWMClass equals the Kotlin WM_CLASS constant`() {
        val script = Files.readString(Path.of("packaging/linux/install.sh"))
        assertThat(script).contains("StartupWMClass=$LINUX_WM_CLASS\n")
    }

    @Test
    fun `WM_CLASS equals the Linux packageName in the build script`() {
        val build = Files.readString(Path.of("build.gradle.kts"))
        assertThat(build).contains("packageName = \"$LINUX_WM_CLASS\"")
    }
}
