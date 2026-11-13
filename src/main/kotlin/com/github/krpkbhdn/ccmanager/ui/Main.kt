package com.github.krpkbhdn.ccmanager.ui

import androidx.compose.ui.window.application
import com.github.krpkbhdn.ccmanager.CcManagerApplication
import com.github.krpkbhdn.ccmanager.claude.hook.HookPoster
import com.github.krpkbhdn.ccmanager.ui.state.Backend
import com.github.krpkbhdn.ccmanager.ui.terminal.TerminalHost
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == HookPoster.FLAG) {
        exitProcess(HookPoster.run(args))
    }
    val context = CcManagerApplication.start(args)
    val backend = Backend.from(context)
    TerminalHost.configure(backend.sessions)
    application { AppWindow(backend, onShutdown = context::close) }
}
