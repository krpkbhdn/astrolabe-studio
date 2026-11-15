package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.DetectedTechnology
import com.github.krpkbhdn.ccmanager.toolchain.ToolState

enum class LspLevel { GREEN, YELLOW, RED }

/** What the status bar LSP indicator shows: the dot [level] and the tooltip [lines] (title first, then one per technology). */
data class LspIndicator(val level: LspLevel, val lines: List<String>)

const val LSP_HINT = "Click to set up languages"
private const val LSP_TITLE = "Language servers (LSP)"

/** Red if any server is installed but broke, else yellow if any is missing, else green; null when nothing was detected. */
fun lspIndicatorOf(detected: List<DetectedTechnology>): LspIndicator? {
    if (detected.isEmpty()) return null
    val states = detected.map { it.serverState() }
    val level = when {
        ToolState.FAILED in states -> LspLevel.RED
        ToolState.MISSING in states -> LspLevel.YELLOW
        else -> LspLevel.GREEN
    }
    return LspIndicator(level, listOf(LSP_TITLE) + detected.map(::lineOf))
}

private fun lineOf(tech: DetectedTechnology): String = "${tech.tool().name()}: " + when (tech.serverState()) {
    ToolState.RESPONDING -> "running"
    ToolState.INSTALLED -> "installed"
    ToolState.MISSING -> "not installed"
    ToolState.FAILED -> "not responding"
}
