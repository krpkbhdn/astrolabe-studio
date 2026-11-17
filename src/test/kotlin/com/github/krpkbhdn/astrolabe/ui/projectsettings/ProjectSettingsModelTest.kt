package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolRow
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.ToolKind
import com.github.krpkbhdn.astrolabe.toolchain.ToolState
import com.github.krpkbhdn.astrolabe.toolchain.ToolStatus
import com.github.krpkbhdn.astrolabe.ui.tools.ChipTone
import com.github.krpkbhdn.astrolabe.ui.tools.StatusChipView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProjectSettingsModelTest {
    private fun row(
        id: String, kind: ToolKind, group: String, plugin: String = "$id-lsp@market",
        state: ToolState = ToolState.INSTALLED, detected: Boolean = false, enabled: Boolean = false, userLevel: Boolean = false,
    ) = ProjectToolRow(
        ToolDefinition(id, id.uppercase(), group, kind, id, listOf(), plugin, "", listOf(), mapOf(), "", mapOf(), mapOf()),
        ToolStatus(id, state, "", ""), detected, enabled, userLevel,
    )

    private val typescript = row("ts", ToolKind.LANGUAGE_SERVER, "Web")
    private val go = row("gopls", ToolKind.LANGUAGE_SERVER, "Go")

    @Test
    fun `groups with a detected tool come first and keep their order otherwise`() {
        val groups = languageGroups(listOf(go, row("rust", ToolKind.LANGUAGE_SERVER, "Rust", detected = true), typescript))

        assertThat(groups.map { it.title }).containsExactly("Rust", "Go", "Web")
        assertThat(groups.map { it.detected }).containsExactly(true, false, false)
    }

    @Test
    fun `install hint shows when the server is missing`() {
        assertThat(installHintOf(row("ts", ToolKind.LANGUAGE_SERVER, "Web", state = ToolState.MISSING))).isEqualTo(INSTALL_FIRST_HINT)
    }

    @Test
    fun `no hint next to a blocked install reason`() {
        val missing = row("ts", ToolKind.LANGUAGE_SERVER, "Web", state = ToolState.MISSING)

        assertThat(installHintOf(missing, installBlocked = true)).isNull()
    }

    @Test
    fun `no hint for an installed server`() {
        assertThat(installHintOf(typescript)).isNull()
    }

    @Test
    fun `toggle reflects the plugin and is off while the row works`() {
        val on = row("ts", ToolKind.LANGUAGE_SERVER, "Web", enabled = true)

        assertThat(toggleOf(on, working = false)).isEqualTo(ToggleView(checked = true, enabled = true, note = null))
        assertThat(toggleOf(on, working = true).enabled).isFalse()
    }

    @Test
    fun `a server without any plugin cannot be toggled`() {
        val odd = row("odd", ToolKind.LANGUAGE_SERVER, "Misc", plugin = "")

        assertThat(toggleOf(odd, false)).isEqualTo(ToggleView(checked = false, enabled = false, note = NO_PLUGIN_NOTE))
    }

    @Test
    fun `a plugin on only at user level shows a chip, a disabled off switch and the nothing-to-do note`() {
        val userLevel = row("ts", ToolKind.LANGUAGE_SERVER, "Web", userLevel = true)

        assertThat(toggleOf(userLevel, working = false)).isEqualTo(ToggleView(checked = false, enabled = false, note = USER_LEVEL_NOTE))
        assertThat(pluginChipOf(userLevel)).isEqualTo(StatusChipView(USER_LEVEL_CHIP, ChipTone.SUCCESS, null))
    }

    @Test
    fun `a project plugin wins over the user level one`() {
        val both = row("ts", ToolKind.LANGUAGE_SERVER, "Web", enabled = true, userLevel = true)

        assertThat(toggleOf(both, false)).isEqualTo(ToggleView(checked = true, enabled = true, note = null))
        assertThat(pluginChipOf(both)).isNull()
    }

    @Test
    fun `a plain row with its own plugin has no chip`() {
        assertThat(pluginChipOf(typescript)).isNull()
    }
}
