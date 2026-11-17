package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess
import com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.Mode
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ToolCallAccessModelTest {

    private fun row(name: String, selected: Boolean, reason: String? = null) = CodeModeRow(name, ExtensionScope.USER, selected, reason, null)

    @Test
    fun `the chip names Auto only when tool calls run without asking`() {
        assertThat(chipLabelOf(ToolCallAccess.ASK)).isEqualTo("Code Mode")
        assertThat(chipLabelOf(ToolCallAccess.ALL)).isEqualTo("Code Mode · Auto")
        assertThat(chipLabelOf(ToolCallAccess.selected(listOf("a")))).isEqualTo("Code Mode · Auto")
        assertThat(accessLineOf(ToolCallAccess.ASK)).isNull()
        assertThat(accessLineOf(ToolCallAccess.ALL)).contains("all servers")
        assertThat(accessLineOf(ToolCallAccess.selected(listOf("a", "b")))).contains("a, b")
    }

    @Test
    fun `only routed servers that can run are offered`() {
        val rows = listOf(row("a", true), row("b", false), row("c", true, "needs sign-in"))

        assertThat(offeredServers(rows)).containsExactly("a")
    }

    @Test
    fun `switching the mode keeps chosen servers only for Selected and a server toggle selects`() {
        val chosen = ToolCallAccess.selected(listOf("a"))

        assertThat(accessWithMode(chosen, Mode.ALL)).isEqualTo(ToolCallAccess.ALL)
        assertThat(accessWithMode(chosen, Mode.ASK)).isEqualTo(ToolCallAccess.ASK)
        assertThat(accessWithMode(ToolCallAccess.ALL, Mode.SELECTED)).isEqualTo(ToolCallAccess.selected(emptyList()))
        assertThat(accessWithServer(ToolCallAccess.ALL, "b", true)).isEqualTo(ToolCallAccess.selected(listOf("b")))
        assertThat(accessWithServer(chosen, "a", false)).isEqualTo(ToolCallAccess.selected(emptyList()))
    }
}
