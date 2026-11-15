package com.github.krpkbhdn.ccmanager.ui.state

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuitWarningTest {
    @Test
    fun `nothing running means no warning`() {
        assertThat(quitWarningOf(0, installRunning = false)).isNull()
    }

    @Test
    fun `sessions are counted with the right noun`() {
        assertThat(quitWarningOf(1, false)).isEqualTo("1 Claude session is running and will be terminated.")
        assertThat(quitWarningOf(3, false)).isEqualTo("3 Claude sessions are running and will be terminated.")
    }

    @Test
    fun `a running install alone is warned about`() {
        assertThat(quitWarningOf(0, true)).isEqualTo("A tool install is still running. Quitting stops it and may leave the tool half-installed.")
    }

    @Test
    fun `sessions and an install are both named`() {
        assertThat(quitWarningOf(2, true)).startsWith("2 Claude sessions").endsWith(INSTALL_RUNNING_WARNING)
    }
}
