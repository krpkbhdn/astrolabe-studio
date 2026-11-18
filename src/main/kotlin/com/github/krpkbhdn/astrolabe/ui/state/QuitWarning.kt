package com.github.krpkbhdn.astrolabe.ui.state

const val INSTALL_RUNNING_WARNING = "A tool install is still running. Quitting stops it and may leave the tool half-installed."

/** What quitting would cost, or null when nothing is lost and the app may close without asking. */
fun quitWarningOf(runningSessions: Int, installRunning: Boolean): String? {
    val sessions = when (runningSessions) {
        0 -> null
        1 -> "1 Claude session is running and will be terminated."
        else -> "$runningSessions Claude sessions are running and will be terminated."
    }
    return listOfNotNull(sessions, INSTALL_RUNNING_WARNING.takeIf { installRunning }).joinToString(" ").ifEmpty { null }
}
