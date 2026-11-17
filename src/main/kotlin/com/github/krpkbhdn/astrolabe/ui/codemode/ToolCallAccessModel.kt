package com.github.krpkbhdn.astrolabe.ui.codemode

import com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess
import com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.Mode

const val TOOL_CALLS_TITLE = "Tool calls without asking"
const val INHERITED_LABEL = "Inherited"
private const val CHIP_LABEL = "Code Mode"

/** Chip text: the plain name for Ask, a short suffix when tool calls run without asking. */
fun chipLabelOf(access: ToolCallAccess): String = if (access.mode == Mode.ASK) CHIP_LABEL else "$CHIP_LABEL · Auto"

/** The tooltip line naming the mode; null for Ask, which is the default. */
fun accessLineOf(access: ToolCallAccess): String? = when (access.mode) {
    Mode.ASK -> null
    Mode.ALL -> "$TOOL_CALLS_TITLE: all servers"
    Mode.SELECTED -> "$TOOL_CALLS_TITLE: " + if (access.servers.isEmpty()) "no server chosen" else access.servers.joinToString()
}

fun modeNoteOf(mode: Mode): String = when (mode) {
    Mode.ASK -> "Every tool that is not read-only asks first."
    Mode.ALL -> "Tools of all Code Mode servers run without asking, in every session of this project. Deny rules of Claude Code still apply."
    Mode.SELECTED -> "Tools of the chosen servers run without asking, in every session of this project. Deny rules of Claude Code still apply."
}

/** Servers that can be chosen: the ones routed through Code Mode that can run; ineligible ones are not offered. */
fun offeredServers(rows: List<CodeModeRow>): List<String> = rows.filter { it.selected && it.eligible }.map { it.name }

fun accessWithMode(current: ToolCallAccess, mode: Mode): ToolCallAccess = when (mode) {
    Mode.ASK -> ToolCallAccess.ASK
    Mode.ALL -> ToolCallAccess.ALL
    Mode.SELECTED -> ToolCallAccess.selected(current.servers)
}

fun accessWithServer(current: ToolCallAccess, name: String, on: Boolean): ToolCallAccess =
    ToolCallAccess.selected(withServer(current.servers, name, on))
