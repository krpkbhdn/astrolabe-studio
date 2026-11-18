package com.github.krpkbhdn.astrolabe.ui

import androidx.compose.ui.window.application
import com.github.krpkbhdn.astrolabe.AstrolabeApplication
import com.github.krpkbhdn.astrolabe.claude.hook.HookPoster
import com.github.krpkbhdn.astrolabe.ui.state.Backend
import com.github.krpkbhdn.astrolabe.ui.terminal.TerminalHost
import com.github.krpkbhdn.astrolabe.ui.terminal.TerminalProperties
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == HookPoster.FLAG) {
        exitProcess(HookPoster.run(args))
    }
    val context = AstrolabeApplication.start(args)
    val backend = Backend.from(context)
    TerminalHost.configure(backend.sessions, context.getBean(TerminalProperties::class.java))
    application { AppWindow(backend, onShutdown = context::close) }
}
