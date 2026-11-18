package com.github.krpkbhdn.astrolabe.ui.queue

import com.github.krpkbhdn.astrolabe.claude.event.ActivityFormatter
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.ui.state.commandBlockedReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val QUEUE_RETRY_MILLIS = 1_000L
const val QUEUE_MAX_RETRIES = 600
const val QUEUE_SLOW_RETRY_MILLIS = 15_000L

/** Sends queued prompts through [sendPrompt] as [QueueDispatcher] allows and re-checks on a timer while one waits: every [retryMillis] for [maxRetries] checks, then every [slowRetryMillis] so a long-blocked session still gets its prompt. Runs on [scope] only. */
class QueueDispatch(
    private val queues: PromptQueues,
    private val sessions: () -> List<SessionSnapshot>,
    private val draftMayExist: (String) -> Boolean,
    private val isAttached: (String) -> Boolean,
    private val sendPrompt: suspend (astrolabeSessionId: String, text: String) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = QUEUE_RETRY_MILLIS,
    private val maxRetries: Int = QUEUE_MAX_RETRIES,
    private val slowRetryMillis: Long = QUEUE_SLOW_RETRY_MILLIS,
) {
    private val dispatcher = QueueDispatcher()
    private val sending = mutableMapOf<String, Job>()
    private var retry: Job? = null
    private var disposed = false
    private var retries = 0

    fun promptAdded(astrolabeSessionId: String) = dispatcher.promptAdded(astrolabeSessionId)

    fun evaluate() {
        retries = 0
        check()
    }

    /** Like [evaluate] for hook events: nothing is queued and nothing pending, so only the per-session tracking is dropped. */
    fun sessionsChanged() {
        if (queues.isEmpty() && retry?.isActive != true && sending.isEmpty()) dispatcher.retainSessions(emptySet()) else evaluate()
    }

    private fun check() {
        val snapshots = sessions()
        val live = snapshots.map { it.astrolabeSessionId }.toSet()
        queues.retainSessions(live)
        dispatcher.retainSessions(live)
        var waiting = false
        snapshots.forEach { session ->
            if (dispatcher.observe(session.astrolabeSessionId, observationOf(session)) == QueueDecision.SEND) dispatch(session.astrolabeSessionId)
            waiting = waiting || dispatcher.needsRecheck(session.astrolabeSessionId)
        }
        if (waiting) scheduleRetry()
    }

    fun dispose() {
        disposed = true
        retry?.cancel()
        sending.values.forEach { it.cancel() }
        sending.clear()
    }

    private fun observationOf(session: SessionSnapshot): QueueObservation {
        val id = session.astrolabeSessionId
        return QueueObservation(
            status = session.status,
            interrupted = session.activity == ActivityFormatter.INTERRUPTED,
            queueEmpty = queues.of(id).isEmpty(),
            draftExists = draftMayExist(id),
            blockedReason = commandBlockedReason(session, isAttached(id)),
            nowMillis = clock(),
        )
    }

    private fun dispatch(astrolabeSessionId: String) {
        val prompt = queues.of(astrolabeSessionId).firstOrNull() ?: return
        if (disposed || sending.containsKey(astrolabeSessionId)) return
        sending[astrolabeSessionId] = scope.launch {
            try {
                if (withContext(NonCancellable) { sendPrompt(astrolabeSessionId, prompt.text) }) {
                    queues.remove(astrolabeSessionId, prompt.id)
                    dispatcher.sent(astrolabeSessionId, clock())
                }
            } finally {
                sending.remove(astrolabeSessionId)
            }
            if (dispatcher.needsRecheck(astrolabeSessionId)) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (disposed || retry?.isActive == true) return
        val wait = if (retries >= maxRetries) slowRetryMillis else retryMillis
        if (retries < maxRetries) retries++
        retry = scope.launch {
            delay(wait)
            retry = null
            check()
        }
    }
}
