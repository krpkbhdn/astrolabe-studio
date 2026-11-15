package com.github.krpkbhdn.ccmanager.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.ui.codemode.CODE_MODE_HINT
import com.github.krpkbhdn.ccmanager.ui.codemode.CodeModeLevel
import com.github.krpkbhdn.ccmanager.ui.codemode.CodeModeRow
import com.github.krpkbhdn.ccmanager.ui.codemode.codeModeIndicatorOf
import com.github.krpkbhdn.ccmanager.ui.codemode.codeModeRows
import com.github.krpkbhdn.ccmanager.ui.codemode.sandboxLines
import com.github.krpkbhdn.ccmanager.ui.codemode.unlistedServers
import com.github.krpkbhdn.ccmanager.ui.components.AnchoredPopup
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.components.AppSwitch
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.projectsettings.ProjectSettingsSection
import com.github.krpkbhdn.ccmanager.ui.projectsettings.ServerRow
import com.github.krpkbhdn.ccmanager.ui.settings.IsolationView
import com.github.krpkbhdn.ccmanager.ui.state.AppState
import com.github.krpkbhdn.ccmanager.ui.state.Route
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors

private val panelWidth = 380.dp
private val serverListMaxHeight = 260.dp

/** Status bar Code Mode chip of the project on screen (dot = state of its Code Mode) with the quick-edit popup opening above it. */
@Composable
internal fun CodeModeIndicatorSegment(state: AppState) {
    val route = state.route as? Route.Project ?: return
    val project = state.projects.all.firstOrNull { it.id == route.id }?.takeUnless { it.group } ?: return
    val chip = state.codeModeChip
    val codeMode = chip.codeMode ?: return
    val mcp = chip.mcp ?: return
    val settings = state.codeModeSettings
    val sandbox = settings.selected
    LaunchedEffect(sandbox) { if (IsolationView.isContainer(sandbox) && sandbox !in settings.statuses) settings.refresh(sandbox) }
    if (!codeMode.loaded || !mcp.loaded) return
    val rows = remember(mcp.servers, codeMode.servers, codeMode.plugins) { codeModeRows(mcp.servers, codeMode.servers, emptySet(), codeMode.plugins) }
    val missing = unlistedServers(codeMode.servers, rows)
    val status = settings.statuses[sandbox]
    val indicator = codeModeIndicatorOf(codeMode.enabled, rows, missing, sandbox, status)
    Box {
        StatusDotChip("Code Mode", indicator.lines, CODE_MODE_HINT, dotColor(indicator.level), chip.open) { chip.toggle() }
        if (chip.open) {
            AnchoredPopup({ chip.open = false }, alignEnd = true, modifier = Modifier.width(panelWidth)) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        AppSwitch(codeMode.enabled, codeMode::switchOn, enabled = codeMode.available)
                        Label("Code Mode for this project", Modifier.weight(1f), style = Type.bodyMedium)
                    }
                    sandboxLines(sandbox, status).forEachIndexed { index, line ->
                        Label(line, color = if (index == 0) colors.textSecondary else colors.warning, style = Type.small, maxLines = Int.MAX_VALUE)
                    }
                    ServerList(rows, missing, codeMode.enabled) { name, on -> codeMode.setServer(name, on) }
                    AppButton("Open settings", { chip.open = false; openSettings(state, project) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ServerList(rows: List<CodeModeRow>, missing: List<String>, codeModeOn: Boolean, onChange: (String, Boolean) -> Unit) {
    Column(Modifier.heightIn(max = serverListMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (rows.isEmpty()) Label("This project sees no MCP servers yet.", color = colors.textTertiary, style = Type.small, maxLines = 2)
        rows.forEach { row -> ServerRow(row, codeModeOn) { onChange(row.name, it) } }
        if (missing.isNotEmpty()) Label("Saved but not found any more: ${missing.joinToString()}", color = colors.warning, style = Type.small, maxLines = 3)
    }
}

private fun openSettings(state: AppState, project: Project) = state.extensions.openProjectSettings(project, ProjectSettingsSection.CODE_MODE)

@Composable
private fun dotColor(level: CodeModeLevel): Color = when (level) {
    CodeModeLevel.OFF -> colors.textTertiary
    CodeModeLevel.GREEN -> colors.success
    CodeModeLevel.YELLOW -> colors.warning
    CodeModeLevel.RED -> colors.danger
}
