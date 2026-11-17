package com.github.krpkbhdn.astrolabe.ui.queue

import androidx.compose.runtime.mutableStateMapOf

class QueuedPrompt(val id: Long, val text: String)

/** Per-session FIFO queues of prompts, in memory only; observable so the panel redraws. Used from the UI thread. */
class PromptQueues {
    private val queues = mutableStateMapOf<String, List<QueuedPrompt>>()
    private var nextId = 1L

    fun of(astrolabeSessionId: String): List<QueuedPrompt> = queues[astrolabeSessionId].orEmpty()

    /** Returns the id of the new prompt. */
    fun add(astrolabeSessionId: String, text: String): Long {
        val prompt = QueuedPrompt(nextId++, text)
        queues[astrolabeSessionId] = of(astrolabeSessionId) + prompt
        return prompt.id
    }

    fun remove(astrolabeSessionId: String, promptId: Long) = set(astrolabeSessionId, of(astrolabeSessionId).filter { it.id != promptId })

    fun clear(astrolabeSessionId: String) = set(astrolabeSessionId, emptyList())

    /** Drops the queues of sessions that are no longer live. */
    fun retainSessions(live: Set<String>) = queues.keys.filter { it !in live }.forEach(queues::remove)

    private fun set(astrolabeSessionId: String, prompts: List<QueuedPrompt>) {
        if (prompts.isEmpty()) queues.remove(astrolabeSessionId) else queues[astrolabeSessionId] = prompts
    }
}
