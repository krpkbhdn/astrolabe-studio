package com.github.krpkbhdn.astrolabe.ui.codemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.Mode
import com.github.krpkbhdn.astrolabe.ui.components.AppCheckbox
import com.github.krpkbhdn.astrolabe.ui.components.Chip
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.Segment
import com.github.krpkbhdn.astrolabe.ui.components.SegmentedControl
import com.github.krpkbhdn.astrolabe.ui.components.TextLink
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private val segments = listOf(Segment(Mode.ASK, "Ask"), Segment(Mode.ALL, "All servers"), Segment(Mode.SELECTED, "Selected servers"))

/** The "Tool calls without asking" choice, shared by the status bar popup and the project settings page; both edit the same saved project setting. A group that inherits shows it read-only, with [onOpenGroupSettings] as the way to change it. */
@Composable
fun ToolCallAccessControl(codeMode: ProjectCodeModeState, rows: List<CodeModeRow>, modifier: Modifier = Modifier, onOpenGroupSettings: (() -> Unit)? = null) {
    val access = codeMode.toolCalls
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Label(TOOL_CALLS_TITLE, style = Type.bodyMedium)
            if (codeMode.inherits) Chip(INHERITED_LABEL, colors.accent)
            if (codeMode.inherits && onOpenGroupSettings != null) TextLink("Group settings", onOpenGroupSettings)
        }
        if (codeMode.inherits) Label(segments.first { it.value == access.mode }.label, style = Type.small)
        else if (codeMode.available) SegmentedControl(segments, access.mode, { codeMode.chooseToolCalls(accessWithMode(access, it)) })
        Label(modeNoteOf(access.mode), color = colors.textTertiary, style = Type.caption, maxLines = Int.MAX_VALUE)
        if (access.mode == Mode.SELECTED) {
            val offered = offeredServers(rows)
            if (offered.isEmpty()) Label("No server is routed through Code Mode yet.", color = colors.textTertiary, style = Type.caption, maxLines = 2)
            offered.forEach { name ->
                Row(Modifier.padding(start = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AppCheckbox(name in access.servers, { codeMode.chooseToolCalls(accessWithServer(access, name, it)) }, enabled = !codeMode.inherits)
                    Label(name, style = Type.small, tooltipWhenTruncated = true)
                }
            }
        }
    }
}
