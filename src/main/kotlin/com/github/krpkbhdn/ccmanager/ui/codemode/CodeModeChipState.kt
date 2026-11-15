package com.github.krpkbhdn.ccmanager.ui.codemode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.ui.extensions.mcp.McpState

/** The Code Mode state of the project on screen for the status bar chip: one [ProjectCodeModeState] and [McpState] at a time, which reload on service callbacks so the chip and the settings dialog agree. */
class CodeModeChipState(
    val available: Boolean,
    private val newCodeMode: (Project) -> ProjectCodeModeState,
    private val newMcp: (Project) -> McpState,
) {
    var open by mutableStateOf(false)
    var codeMode by mutableStateOf<ProjectCodeModeState?>(null)
        private set
    var mcp by mutableStateOf<McpState?>(null)
        private set
    private var projectId: Long? = null

    /** Follows [project] from now on; the same project again keeps what is loaded. */
    fun track(project: Project) {
        if (!available || project.group || projectId == project.id) return
        stop()
        projectId = project.id
        codeMode = newCodeMode(project).also { it.load() }
        mcp = newMcp(project).also { it.refresh() }
    }

    fun stop() {
        open = false
        codeMode?.dispose()
        codeMode = null
        mcp = null
        projectId = null
    }

    /** The MCP list may have changed in the settings dialog since the last look. */
    fun toggle() {
        open = !open
        if (open) mcp?.refresh()
    }
}
