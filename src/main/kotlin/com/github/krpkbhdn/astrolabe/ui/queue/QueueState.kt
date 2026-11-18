package com.github.krpkbhdn.astrolabe.ui.queue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry
import com.github.krpkbhdn.astrolabe.ui.state.Refresher
import com.github.krpkbhdn.astrolabe.ui.state.TerminalPort
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope

private const val QUEUE_EVALUATE_MIN_INTERVAL_MILLIS = 100L

/** Prompts waiting for the live sessions to be free; the sending decision lives in [QueueDispatch]. Runs on the UI scope only. */
class QueueState(
    private val registry: SessionRegistry,
    terminal: TerminalPort,
    private val scope: CoroutineScope,
    isAttached: (String) -> Boolean,
    sendPrompt: suspend (astrolabeSessionId: String, text: String) -> Boolean,
    private val reportError: (String) -> Unit,
) {
    private val queues = PromptQueues()
    private val dispatch = QueueDispatch(queues, registry::sessions, terminal::draftMayExist, isAttached, sendPrompt, scope)
    private val evaluation = Refresher(scope, QUEUE_EVALUATE_MIN_INTERVAL_MILLIS) { runGuarded(reportError) { dispatch.sessionsChanged() } }
    private var unsubscribe: Runnable? = null

    /** The session whose queue panel is open, if any. */
    var panelSessionId by mutableStateOf<String?>(null)

    fun of(astrolabeSessionId: String): List<QueuedPrompt> = queues.of(astrolabeSessionId)

    /** Returns the id of the queued prompt, null when the text is blank. */
    fun add(astrolabeSessionId: String, text: String): Long? {
        if (text.isBlank()) return null
        val id = queues.add(astrolabeSessionId, text.trim())
        dispatch.promptAdded(astrolabeSessionId)
        dispatch.evaluate()
        return id
    }

    fun remove(astrolabeSessionId: String, promptId: Long) = queues.remove(astrolabeSessionId, promptId)

    fun clear(astrolabeSessionId: String) = queues.clear(astrolabeSessionId)

    fun start() {
        unsubscribe = registry.subscribe { evaluation.request() }
    }

    fun dispose() {
        unsubscribe?.run()
        evaluation.close()
        dispatch.dispose()
    }
}
