package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.ccmanager.ui.settings.IsolationView
import com.github.krpkbhdn.ccmanager.ui.settings.RuntimeStatus

enum class CodeModeLevel { OFF, GREEN, YELLOW, RED }

/** What the status bar Code Mode chip shows: the dot [level] and the tooltip [lines] (title first). */
data class CodeModeIndicator(val level: CodeModeLevel, val lines: List<String>)

const val CODE_MODE_HINT = "Click to manage Code Mode"
private const val TITLE = "Code Mode"

/** Off is grey; on is red when the chosen sandbox cannot run, yellow when no server is routed or a routed one is unusable or gone, else green. */
fun codeModeIndicatorOf(enabled: Boolean, rows: List<CodeModeRow>, missing: List<String>, backend: IsolationBackend, status: RuntimeStatus?): CodeModeIndicator {
    if (!enabled) return CodeModeIndicator(CodeModeLevel.OFF, listOf("$TITLE: off"))
    val selected = rows.filter { it.selected }
    val problems = selected.filterNot { it.eligible }.map { "${it.title}: ${it.ineligibleReason}" } + missing.map { "$it: saved but not found any more" }
    val warning = IsolationView.warning(backend, status)
    val level = when {
        warning != null -> CodeModeLevel.RED
        selected.isEmpty() && missing.isEmpty() || problems.isNotEmpty() -> CodeModeLevel.YELLOW
        else -> CodeModeLevel.GREEN
    }
    val servers = if (selected.isEmpty() && missing.isEmpty()) "No servers selected" else "${selected.size + missing.size} server(s) through Code Mode"
    return CodeModeIndicator(level, listOf("$TITLE: on", servers) + problems + sandboxLines(backend, status, warning))
}

/** The sandbox line of the popup and tooltip: backend, detection for containers, and the failure warning. */
fun sandboxLines(backend: IsolationBackend, status: RuntimeStatus?, warning: String? = IsolationView.warning(backend, status)): List<String> {
    val detection = if (IsolationView.isContainer(backend)) ": " + IsolationView.detection(status) else ""
    val image = if (status?.imagePresent == false) "The sandbox image is not present; pull it in Settings > Code Mode." else null
    return listOf("Sandbox: ${IsolationView.label(backend)}$detection") + listOfNotNull(image, warning)
}
