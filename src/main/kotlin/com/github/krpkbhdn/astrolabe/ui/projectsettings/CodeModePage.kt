package com.github.krpkbhdn.astrolabe.ui.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.ui.codemode.CodeModeRow
import com.github.krpkbhdn.astrolabe.ui.codemode.ProjectCodeModeState
import com.github.krpkbhdn.astrolabe.ui.codemode.ToolCallAccessControl
import com.github.krpkbhdn.astrolabe.ui.codemode.codeModeRows
import com.github.krpkbhdn.astrolabe.ui.codemode.unlistedServers
import com.github.krpkbhdn.astrolabe.ui.components.AppCheckbox
import com.github.krpkbhdn.astrolabe.ui.components.AppSwitch
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.card
import com.github.krpkbhdn.astrolabe.ui.extensions.ExtensionList
import com.github.krpkbhdn.astrolabe.ui.extensions.ListSkeleton
import com.github.krpkbhdn.astrolabe.ui.extensions.ScopeBadge
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.McpState
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.canAuthenticate
import com.github.krpkbhdn.astrolabe.ui.state.ConfirmRequest
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private const val WHEN_IT_HELPS = "Code Mode lets Claude write one short script that calls your MCP servers, instead of calling each tool by hand. It helps with heavy servers, many calls in a row and large outputs, because only the script's result comes back to the model."
private const val WHEN_NOT = "It adds little with a few tools or small outputs, and Claude Code already defers MCP tool schemas, so idle servers cost little context."
private const val ENABLE_NOTE = "Takes effect for newly started sessions. The isolation environment is chosen in Settings > Code Mode."

/** Per-project Code Mode (for a group, with the switch that inherits it from the member projects): the switch and which of the project's MCP servers its scripts reach. Changes are saved at once. */
@Composable
fun CodeModePage(codeMode: ProjectCodeModeState, mcp: McpState, group: Boolean = false, confirm: (ConfirmRequest) -> Unit = {}) {
    LaunchedEffect(Unit) {
        codeMode.load()
        mcp.ensureLoaded()
    }
    val needsSignIn = remember(mcp.health) { mcp.health.filter { canAuthenticate(it.status()) }.map { it.name() }.toSet() }
    val rows = remember(mcp.servers, codeMode.servers, needsSignIn, codeMode.plugins) { codeModeRows(mcp.servers, codeMode.servers, needsSignIn, codeMode.plugins) }
    if (!codeMode.loaded || !mcp.loaded) {
        ListSkeleton()
        return
    }
    ExtensionList {
        if (group) item(key = "inherit") { GroupInheritSwitch("Code Mode settings", codeMode.inherits, !codeMode.available, confirm, codeMode::override, codeMode::inherit) }
        item(key = "intro") { Intro(codeMode, rows) }
        item(key = "heading") { Label("MCP servers", Modifier.padding(top = Spacing.sm), style = Type.bodyMedium) }
        if (rows.isEmpty()) item(key = "none") {
            Label("This project sees no MCP servers yet. Add one on the MCP servers page.", color = colors.textTertiary, maxLines = 2)
        }
        items(rows, key = { it.key }) { ServerRow(it, codeMode.enabled, !codeMode.inherits, sourceNoteOf(codeMode.sources[it.name])) { on -> codeMode.setServer(it.name, on) } }
        unlistedServers(codeMode.servers, rows).takeIf { it.isNotEmpty() }?.let { missing ->
            item(key = "missing") { Label("Saved but not found any more: ${missing.joinToString()}", color = colors.warning, style = Type.small, maxLines = 3) }
        }
    }
}

@Composable
private fun Intro(codeMode: ProjectCodeModeState, rows: List<CodeModeRow>) {
    Column(Modifier.fillMaxWidth().card().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Label(WHEN_IT_HELPS, color = colors.textSecondary, style = Type.small, maxLines = Int.MAX_VALUE)
        Label(WHEN_NOT, color = colors.textSecondary, style = Type.small, maxLines = Int.MAX_VALUE)
        Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AppSwitch(codeMode.enabled, codeMode::switchOn, enabled = codeMode.available && !codeMode.inherits)
            Label("Enable Code Mode for this project", style = Type.bodyMedium)
        }
        Label(if (codeMode.available) ENABLE_NOTE else "Code Mode is not available in this build.", color = colors.textTertiary, style = Type.caption, maxLines = Int.MAX_VALUE)
        ToolCallAccessControl(codeMode, rows, Modifier.padding(top = Spacing.sm))
    }
}

@Composable
internal fun ServerRow(row: CodeModeRow, codeModeOn: Boolean, editable: Boolean = true, source: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().card().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AppCheckbox(row.selected, onChange, enabled = row.canChange && editable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Label(row.title, style = Type.bodyMedium, tooltipWhenTruncated = true)
                ScopeBadge(row.scope)
            }
            Label("Run through Code Mode", color = if (codeModeOn) colors.textSecondary else colors.textTertiary, style = Type.small)
            source?.let { Label(it, color = colors.textSecondary, style = Type.caption) }
            row.message?.let { note ->
                Label(note, color = if (row.eligible) colors.textTertiary else colors.warning, style = Type.caption, maxLines = Int.MAX_VALUE)
            }
        }
    }
}
