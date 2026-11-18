package com.github.krpkbhdn.astrolabe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.Chip
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.WithTooltip
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import com.github.krpkbhdn.astrolabe.ui.tools.ChannelControls
import com.github.krpkbhdn.astrolabe.ui.tools.ChipTone
import com.github.krpkbhdn.astrolabe.ui.tools.InstallButtonView
import com.github.krpkbhdn.astrolabe.ui.tools.ToolRow
import com.github.krpkbhdn.astrolabe.ui.tools.ToolsState
import com.github.krpkbhdn.astrolabe.ui.tools.includesNoteOf
import com.github.krpkbhdn.astrolabe.ui.tools.installButtonOf
import com.github.krpkbhdn.astrolabe.ui.tools.prerequisiteNote
import com.github.krpkbhdn.astrolabe.ui.tools.statusChipOf

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
            group.rows.forEach { ToolRowView(it, tools) }
        }
    }
    Label("Installed tools are used by Claude Code LSP plugins; after installing, restart running sessions.", color = colors.textSecondary, style = Type.small)
}

@Composable
private fun ToolRowView(row: ToolRow, tools: ToolsState) {
    val tool = row.tool
    val chip = statusChipOf(row.status, tools.isChecking(tool.id))
    val note = prerequisiteNote(tool, tools::nameOf)
    SettingRow(tool.name, note ?: listOfNotNull(tool.binary, includesNoteOf(tool)).joinToString(" - ")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WithTooltip(chip.tooltip) { Chip(chip.text, chip.tone.tint()) }
            ChannelControls(tool, row.status, tools)
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
