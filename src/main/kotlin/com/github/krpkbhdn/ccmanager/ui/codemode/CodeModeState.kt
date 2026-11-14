package com.github.krpkbhdn.ccmanager.ui.codemode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.codemode.policy.ExecutionRegistry
import com.github.krpkbhdn.ccmanager.ui.state.io
import com.github.krpkbhdn.ccmanager.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Whether a session runs a Code Mode script right now, so the tab strip can offer "Stop script". Runs on the UI scope only. */
class CodeModeState(private val executions: ExecutionRegistry?, private val scope: CoroutineScope, private val reportError: (String) -> Unit) {
    private var changes by mutableIntStateOf(0)
    private var unsubscribe: Runnable? = null

    fun start() {
        unsubscribe = executions?.subscribe { scope.launch { changes++ } }
    }

    fun dispose() {
        unsubscribe?.run()
    }

    /** A map lookup, so it is safe to call from composition; recomposes when a script starts or ends. */
    fun isRunning(ccmSessionId: String): Boolean = changes >= 0 && executions?.isRunning(ccmSessionId) == true

    fun stop(ccmSessionId: String) {
        scope.launch { runGuarded(reportError) { io { executions?.cancel(ccmSessionId) } } }
    }
}
