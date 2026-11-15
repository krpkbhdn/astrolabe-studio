package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolRow
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.toolchain.ToolStatus
import com.github.krpkbhdn.ccmanager.ui.tools.ChipTone
import com.github.krpkbhdn.ccmanager.ui.tools.StatusChipView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProjectSettingsModelTest {
    private fun row(
        id: String, kind: ToolKind, group: String, base: String = "", plugin: String = "$id-lsp@market",
        state: ToolState = ToolState.INSTALLED, detected: Boolean = false, enabled: Boolean = false, userLevel: Boolean = false,
    ) = ProjectToolRow(
        ToolDefinition(id, id.uppercase(), group, kind, id, listOf(), plugin, "", base, listOf(), mapOf(), "", mapOf(), mapOf()),
        ToolStatus(id, state, "", ""), detected, enabled, userLevel,
    )

    private val typescript = row("ts", ToolKind.LANGUAGE_SERVER, "Web")
    private val angular = row("angular", ToolKind.ADD_ON, "Web", base = "ts", detected = true)
    private val vue = row("vue", ToolKind.ADD_ON, "Web", base = "ts", plugin = "")
    private val go = row("gopls", ToolKind.LANGUAGE_SERVER, "Go")

    @Test
    fun `groups with a detected tool come first and keep their order otherwise`() {
        val groups = languageGroups(listOf(go, row("rust", ToolKind.LANGUAGE_SERVER, "Rust", detected = true), typescript, angular))

        assertThat(groups.map { it.title }).containsExactly("Rust", "Web", "Go")
        assertThat(groups.map { it.detected }).containsExactly(true, true, false)
    }

    @Test
    fun `a group counts as detected when only its add-on is`() {
        val groups = languageGroups(listOf(typescript, angular))

        assertThat(groups.single().detected).isTrue()
    }

    @Test
    fun `add-ons nest under their base language server`() {
        val web = languageGroups(listOf(typescript, angular, vue, go)).first { it.title == "Web" }

        assertThat(web.rows.map { it.row.tool.id() }).containsExactly("ts")
        assertThat(web.rows.single().extras.map { it.row.tool.id() }).containsExactly("angular", "vue")
        assertThat(web.rows.single().extras.map { it.base?.tool?.id() }).containsOnly("ts")
    }

    @Test
    fun `an add-on whose base is absent stays on top level`() {
        assertThat(languageGroups(listOf(angular)).single().rows.map { it.row.tool.id() }).containsExactly("angular")
    }

    @Test
    fun `install hint shows when the server is missing, only the server row carries it, never its add-ons`() {
        val missingBase = row("ts", ToolKind.LANGUAGE_SERVER, "Web", state = ToolState.MISSING)
        val view = languageGroups(listOf(missingBase, vue, angular)).single().rows.single()

        assertThat(installHintOf(view)).isEqualTo(INSTALL_FIRST_HINT)
        assertThat(installHintOf(view.extras.first { it.row.tool.id() == "vue" })).isNull()
        assertThat(installHintOf(view.extras.first { it.row.tool.id() == "angular" })).isNull()
    }

    @Test
    fun `no hint next to a blocked install reason`() {
        val view = languageGroups(listOf(row("ts", ToolKind.LANGUAGE_SERVER, "Web", state = ToolState.MISSING))).single().rows.single()

        assertThat(installHintOf(view, installBlocked = true)).isNull()
    }

    @Test
    fun `no hint for an installed server`() {
        assertThat(installHintOf(languageGroups(listOf(typescript)).single().rows.single())).isNull()
    }

    @Test
    fun `toggle reflects the plugin and is off while the row works`() {
        val on = LanguageRowView(row("ts", ToolKind.LANGUAGE_SERVER, "Web", enabled = true))

        assertThat(toggleOf(on, working = false)).isEqualTo(ToggleView(checked = true, enabled = true, note = null))
        assertThat(toggleOf(on, working = true).enabled).isFalse()
    }

    @Test
    fun `an add-on without a plugin uses the base server and has no toggle of its own`() {
        val view = languageGroups(listOf(typescript, vue)).single().rows.single().extras.single()

        assertThat(sharesBaseServer(view)).isTrue()
        assertThat(toggleOf(view, false)).isEqualTo(ToggleView(checked = false, enabled = false, note = "Uses the TS server"))
    }

    @Test
    fun `a server without any plugin cannot be toggled`() {
        val view = LanguageRowView(row("odd", ToolKind.LANGUAGE_SERVER, "Misc", plugin = ""))

        assertThat(toggleOf(view, false)).isEqualTo(ToggleView(checked = false, enabled = false, note = NO_PLUGIN_NOTE))
    }

    @Test
    fun `framework extras title names the base server`() {
        assertThat(frameworkExtrasTitle(LanguageRowView(typescript))).isEqualTo("Framework extras (on top of the TS server)")
    }

    @Test
    fun `a plugin on only at user level shows a chip, a disabled off switch and the nothing-to-do note`() {
        val view = LanguageRowView(row("ts", ToolKind.LANGUAGE_SERVER, "Web", userLevel = true))

        assertThat(toggleOf(view, working = false)).isEqualTo(ToggleView(checked = false, enabled = false, note = USER_LEVEL_NOTE))
        assertThat(pluginChipOf(view)).isEqualTo(StatusChipView(USER_LEVEL_CHIP, ChipTone.SUCCESS, null))
    }

    @Test
    fun `a project plugin wins over the user level one`() {
        val view = LanguageRowView(row("ts", ToolKind.LANGUAGE_SERVER, "Web", enabled = true, userLevel = true))

        assertThat(toggleOf(view, false)).isEqualTo(ToggleView(checked = true, enabled = true, note = null))
        assertThat(pluginChipOf(view)).isNull()
    }

    @Test
    fun `a shared add-on shows the state of its base server including the user level`() {
        fun chipOf(base: ProjectToolRow) = pluginChipOf(languageGroups(listOf(base, vue)).single().rows.single().extras.single())

        assertThat(chipOf(row("ts", ToolKind.LANGUAGE_SERVER, "Web"))?.text).isEqualTo("Off")
        assertThat(chipOf(row("ts", ToolKind.LANGUAGE_SERVER, "Web", enabled = true))?.text).isEqualTo("Enabled")
        assertThat(chipOf(row("ts", ToolKind.LANGUAGE_SERVER, "Web", userLevel = true))?.text).isEqualTo(USER_LEVEL_CHIP)
    }

    @Test
    fun `a plain row with its own plugin has no chip`() {
        assertThat(pluginChipOf(LanguageRowView(typescript))).isNull()
    }
}
