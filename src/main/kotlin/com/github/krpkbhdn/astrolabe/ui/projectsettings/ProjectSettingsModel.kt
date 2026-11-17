package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolRow
import com.github.krpkbhdn.astrolabe.toolchain.ToolState
import com.github.krpkbhdn.astrolabe.ui.tools.ChipTone
import com.github.krpkbhdn.astrolabe.ui.tools.StatusChipView

const val INSTALL_FIRST_HINT = "Install the server first, otherwise Claude Code reports Executable not found"
const val USER_LEVEL_NOTE = "Already enabled for your user. Nothing to do here."
const val USER_LEVEL_CHIP = "On (user level)"
const val NO_PLUGIN_NOTE = "No Claude Code plugin for this server"

data class LanguageGroupView(val title: String, val detected: Boolean, val rows: List<ProjectToolRow>)

data class ToggleView(val checked: Boolean, val enabled: Boolean, val note: String?)

/** Groups of the catalog in catalog order, those the project uses first. */
fun languageGroups(rows: List<ProjectToolRow>): List<LanguageGroupView> =
    rows.groupBy { it.tool.group() }
        .map { (title, members) -> LanguageGroupView(title, members.any { it.detected() }, members) }
        .sortedByDescending { it.detected }

private fun hasPlugin(row: ProjectToolRow): Boolean = row.tool.lspPlugin().isNotEmpty()

/** Shown once per group, under the server row itself, and never next to a blocked install reason that already says it. */
fun installHintOf(row: ProjectToolRow, installBlocked: Boolean = false): String? =
    INSTALL_FIRST_HINT.takeIf { !installBlocked && row.status?.state() == ToolState.MISSING }

/** The plugin is on for the user but not for this project: nothing to switch here. */
fun enabledOnlyForUser(row: ProjectToolRow): Boolean = row.userLevelEnabled() && !row.pluginEnabled()

fun toggleOf(row: ProjectToolRow, working: Boolean, readOnly: Boolean = false): ToggleView = ToggleView(
    checked = row.pluginEnabled(),
    enabled = hasPlugin(row) && !working && !readOnly && !enabledOnlyForUser(row),
    note = when {
        !hasPlugin(row) -> NO_PLUGIN_NOTE
        enabledOnlyForUser(row) -> USER_LEVEL_NOTE
        else -> null
    },
)

/** A row with its own plugin shows a chip only for the user-level case. */
fun pluginChipOf(row: ProjectToolRow): StatusChipView? = when {
    !enabledOnlyForUser(row) -> null
    else -> StatusChipView(USER_LEVEL_CHIP, ChipTone.SUCCESS, null)
}
