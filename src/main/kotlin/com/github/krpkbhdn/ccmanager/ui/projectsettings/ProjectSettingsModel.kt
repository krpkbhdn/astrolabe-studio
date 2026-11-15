package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolRow
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.ui.tools.ChipTone
import com.github.krpkbhdn.ccmanager.ui.tools.StatusChipView
import com.github.krpkbhdn.ccmanager.ui.tools.nestAddOns

const val INSTALL_FIRST_HINT = "Install the server first, otherwise Claude Code reports Executable not found"
const val USER_LEVEL_NOTE = "Already enabled for your user. Nothing to do here."
const val USER_LEVEL_CHIP = "On (user level)"
const val NO_PLUGIN_NOTE = "No Claude Code plugin for this server"

/** One language server of a project; an add-on carries its [base] row, a language server its add-ons in [extras]. */
data class LanguageRowView(val row: ProjectToolRow, val base: ProjectToolRow? = null, val extras: List<LanguageRowView> = emptyList())

data class LanguageGroupView(val title: String, val detected: Boolean, val rows: List<LanguageRowView>)

data class ToggleView(val checked: Boolean, val enabled: Boolean, val note: String?)

/** Groups of the catalog in catalog order, those the project uses first; add-ons nest under their base language server. */
fun languageGroups(rows: List<ProjectToolRow>): List<LanguageGroupView> {
    val nesting = nestAddOns(rows) { it.tool }
    fun view(row: ProjectToolRow) = LanguageRowView(row, extras = nesting.extrasOf(row).map { LanguageRowView(it, base = row) })
    return nesting.topLevel
        .groupBy { it.tool.group() }
        .map { (title, members) -> members.map(::view).let { views -> LanguageGroupView(title, views.any(::usedByProject), views) } }
        .sortedByDescending { it.detected }
}

private fun usedByProject(view: LanguageRowView): Boolean = view.row.detected() || view.extras.any { it.row.detected() }

/** An add-on without a plugin of its own is served by its base language server. */
fun sharesBaseServer(view: LanguageRowView): Boolean = view.base != null && view.row.tool.lspPlugin().isEmpty()

private fun hasPlugin(view: LanguageRowView): Boolean = view.row.tool.lspPlugin().isNotEmpty() || view.base?.tool?.lspPlugin()?.isNotEmpty() == true

/** Shown once per group, under the server row itself, and never next to a blocked install reason that already says it. */
fun installHintOf(view: LanguageRowView, installBlocked: Boolean = false): String? =
    INSTALL_FIRST_HINT.takeIf { !installBlocked && view.base == null && view.row.status?.state() == ToolState.MISSING }

/** The plugin is on for the user but not for this project: nothing to switch here. */
fun enabledOnlyForUser(row: ProjectToolRow): Boolean = row.userLevelEnabled() && !row.pluginEnabled()

fun toggleOf(view: LanguageRowView, working: Boolean): ToggleView = ToggleView(
    checked = view.row.pluginEnabled(),
    enabled = hasPlugin(view) && !sharesBaseServer(view) && !working && !enabledOnlyForUser(view.row),
    note = when {
        sharesBaseServer(view) -> "Uses the ${view.base?.tool?.name()} server"
        !hasPlugin(view) -> NO_PLUGIN_NOTE
        enabledOnlyForUser(view.row) -> USER_LEVEL_NOTE
        else -> null
    },
)

/** A shared add-on shows the state of its base server; a row with its own plugin shows a chip only for the user-level case. */
fun pluginChipOf(view: LanguageRowView): StatusChipView? {
    val subject = if (sharesBaseServer(view)) view.base else view.row.takeIf(::enabledOnlyForUser)
    return when {
        subject == null -> null
        subject.pluginEnabled() -> StatusChipView("Enabled", ChipTone.SUCCESS, null)
        enabledOnlyForUser(subject) -> StatusChipView(USER_LEVEL_CHIP, ChipTone.SUCCESS, null)
        else -> StatusChipView("Off", ChipTone.MUTED, null)
    }
}

fun frameworkExtrasTitle(base: LanguageRowView): String = "Framework extras (on top of the ${base.row.tool.name()} server)"
