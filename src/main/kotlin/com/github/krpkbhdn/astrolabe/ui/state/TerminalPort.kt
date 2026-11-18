package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.claude.launch.LaunchedSession
import com.github.krpkbhdn.astrolabe.shell.LaunchedShell
import com.github.krpkbhdn.astrolabe.ui.terminal.TerminalHost
import com.github.krpkbhdn.astrolabe.ui.terminal.TerminalView
import com.github.krpkbhdn.astrolabe.ui.theme.colors

/** The terminal widget layer, behind an interface so the UI can be previewed without a real PTY. */
interface TerminalPort {
    fun open(session: LaunchedSession)
    fun openShell(shell: LaunchedShell) {}
    fun send(astrolabeSessionId: String, text: String)
    fun close(astrolabeSessionId: String)

    /** True while closed terminals still have processes being ended. */
    fun closing(): Boolean = false

    /** False when the session has no terminal, so [send] would reach nobody. */
    fun hasTerminal(astrolabeSessionId: String): Boolean = true

    /** Moves the keyboard focus into the session's terminal, if it has one. */
    fun focus(astrolabeSessionId: String) {}

    /** True when a Ctrl+C reached the session less than [windowMillis] ago. */
    fun ctrlCWithin(astrolabeSessionId: String, windowMillis: Long): Boolean = false

    /** True when the user pressed Enter less than [windowMillis] ago, before the hook marks the session WORKING. */
    fun enterWithin(astrolabeSessionId: String, windowMillis: Long): Boolean = false

    /** True while the user may have an unsent draft: typed or pasted since the last Enter or Ctrl+C. */
    fun draftMayExist(astrolabeSessionId: String): Boolean = false

    /** The text currently on the session's terminal screen; empty without a terminal. */
    fun screenText(astrolabeSessionId: String): String = ""

    @Composable
    fun View(astrolabeSessionId: String, modifier: Modifier)
}

object HostTerminalPort : TerminalPort {
    override fun open(session: LaunchedSession) = TerminalHost.open(session)
    override fun openShell(shell: LaunchedShell) = TerminalHost.open(shell)
    override fun send(astrolabeSessionId: String, text: String) = TerminalHost.send(astrolabeSessionId, text)
    override fun close(astrolabeSessionId: String) = TerminalHost.close(astrolabeSessionId)
    override fun closing() = TerminalHost.closing()
    override fun hasTerminal(astrolabeSessionId: String) = TerminalHost.hasTerminal(astrolabeSessionId)
    override fun focus(astrolabeSessionId: String) = TerminalHost.focus(astrolabeSessionId)
    override fun ctrlCWithin(astrolabeSessionId: String, windowMillis: Long) = TerminalHost.ctrlCWithin(astrolabeSessionId, windowMillis)
    override fun enterWithin(astrolabeSessionId: String, windowMillis: Long) = TerminalHost.enterWithin(astrolabeSessionId, windowMillis)
    override fun draftMayExist(astrolabeSessionId: String) = TerminalHost.draftMayExist(astrolabeSessionId)
    override fun screenText(astrolabeSessionId: String) = TerminalHost.sessionOf(astrolabeSessionId)?.screenText().orEmpty()

    @Composable
    override fun View(astrolabeSessionId: String, modifier: Modifier) = TerminalView(astrolabeSessionId, modifier, dark = colors.dark, background = colors.terminal)
}
