package com.github.krpkbhdn.ccmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.components.Chip
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.WithTooltip
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors
import com.github.krpkbhdn.ccmanager.ui.tools.ChipTone
import com.github.krpkbhdn.ccmanager.ui.tools.InstallButtonView
import com.github.krpkbhdn.ccmanager.ui.tools.ToolRow
import com.github.krpkbhdn.ccmanager.ui.tools.ToolsState
import com.github.krpkbhdn.ccmanager.ui.tools.includesNoteOf
import com.github.krpkbhdn.ccmanager.ui.tools.installButtonOf
import com.github.krpkbhdn.ccmanager.ui.tools.prerequisiteNote
import com.github.krpkbhdn.ccmanager.ui.tools.statusChipOf

private val extrasIndent = Spacing.xl

@Composable
fun ToolsPage(tools: ToolsState) {
    LaunchedEffect(Unit) { tools.open() }
    SettingGroup("Detection") {
        SettingRow("Check tools", "Looks for each tool on this computer; language servers are also asked to start.") {
            AppButton("Check all", tools::checkAll, enabled = !tools.checkingAll, loading = tools.checkingAll)
        }
    }
    tools.groups.forEach { group ->
        SettingGroup(group.title) {
            group.rows.forEach { row ->
                ToolRowView(row, tools)
                if (row.extras.isNotEmpty()) ExtrasOf(row, tools)
            }
        }
    }
    Label("Installed tools are used by Claude Code LSP plugins; after installing, restart running sessions.", color = colors.textSecondary, style = Type.small)
}

@Composable
private fun ExtrasOf(base: ToolRow, tools: ToolsState) {
    Column(Modifier.fillMaxWidth().padding(start = extrasIndent), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Label("Extras for ${base.tool.name}", color = colors.textTertiary, style = Type.overline)
        base.extras.forEach { ToolRowView(it, tools) }
    }
}

@Composable
private fun ToolRowView(row: ToolRow, tools: ToolsState) {
    val tool = row.tool
    val chip = statusChipOf(row.status, tools.isChecking(tool.id))
    val note = prerequisiteNote(tool, tools::nameOf)
    SettingRow(tool.name, note ?: listOfNotNull(tool.binary, includesNoteOf(tool)).joinToString(" - ")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WithTooltip(chip.tooltip) { Chip(chip.text, chip.tone.tint()) }
            val install = installButtonOf(row.status, tools.plans[tool.id], tool.docsUrl, tools.isManaged(tool.id))
            InstallButtonView(install, "Install", { tools.requestInstall(tool.id) }, tools::openDocs, { tools.requestUninstall(tool.id) })
            AppButton("Check", { tools.check(tool.id) }, compact = true, enabled = !tools.isChecking(tool.id))
        }
    }
}

@Composable
internal fun ChipTone.tint(): Color = when (this) {
    ChipTone.MUTED -> colors.textTertiary
    ChipTone.NEUTRAL -> colors.textSecondary
    ChipTone.SUCCESS -> colors.success
    ChipTone.WARNING -> colors.warning
    ChipTone.DANGER -> colors.danger
}
