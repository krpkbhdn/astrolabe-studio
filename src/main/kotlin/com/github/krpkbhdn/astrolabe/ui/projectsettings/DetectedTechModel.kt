package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.toolchain.DetectedTechnology
import com.github.krpkbhdn.astrolabe.toolchain.LspPluginState
import com.github.krpkbhdn.astrolabe.toolchain.ToolState

enum class LspLevel { GREEN, YELLOW, RED, GRAY }

/** What the status bar LSP indicator shows: the dot [level], the tooltip [lines] (title first) and [warningCount], the languages left without their plugin while others have it (a marker). */
data class LspIndicator(val level: LspLevel, val lines: List<String>, val warningCount: Int = 0)

const val LSP_HINT = "Click to set up languages"
private const val LSP_TITLE = "Language servers (LSP)"
private const val LSP_PLUGINS_OFF = "LSP plugins are disabled for this project"

/** Only languages whose LSP plugin is on count: red if a server is installed but broke, else yellow if one is missing, else green; gray when every plugin is off; a partly enabled set keeps that colour and warns about the rest; null when nothing was detected. */
fun lspIndicatorOf(detected: List<DetectedTechnology>): LspIndicator? {
    if (detected.isEmpty()) return null
    val (checked, skipped) = detected.partition { it.pluginState().serverMatters() }
    if (checked.isEmpty()) return LspIndicator(LspLevel.GRAY, listOf(LSP_TITLE, LSP_PLUGINS_OFF))
    val states = checked.mapNotNull { it.serverState() }
    val level = when {
        ToolState.FAILED in states -> LspLevel.RED
        ToolState.MISSING in states -> LspLevel.YELLOW
        else -> LspLevel.GREEN
    }
    val notEnabled = if (skipped.isEmpty()) listOf() else listOf("Not enabled: " + skipped.joinToString { "${it.tool().name()} (${pluginProblemOf(it)})" })
    return LspIndicator(level, listOf(LSP_TITLE) + checked.map(::lineOf) + notEnabled, skipped.size)
}

private fun pluginProblemOf(tech: DetectedTechnology): String =
    if (tech.pluginState() == LspPluginState.NOT_INSTALLED) "plugin not installed" else "plugin disabled"

private fun lineOf(tech: DetectedTechnology): String = "${tech.tool().name()}: " + when (tech.serverState()) {
    ToolState.RESPONDING -> "running"
    ToolState.INSTALLED -> "installed"
    ToolState.MISSING -> "not installed"
    ToolState.FAILED -> "not responding"
    null -> "not checked"
}
