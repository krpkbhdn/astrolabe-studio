package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.team.Team
import com.github.krpkbhdn.astrolabe.ui.review.SessionChoice
import com.github.krpkbhdn.astrolabe.ui.review.TeamOptions
import com.github.krpkbhdn.astrolabe.ui.state.LiveSessions
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import com.github.krpkbhdn.astrolabe.web.WebReviewService
import com.github.krpkbhdn.astrolabe.web.WebRevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class WebSendTarget(val revision: WebRevision, val candidates: List<SessionChoice>, val teams: TeamOptions = TeamOptions.NONE)

typealias SessionEnqueue = (astrolabeSessionId: String, text: String) -> Unit

typealias SessionStarter = (projectId: Long, team: Team?, prompt: String, onNotStarted: () -> Unit, onStarted: (String) -> Unit) -> Unit

private const val START_FAILED = "Could not start a session"

/** Sends a revision to an agent: the session chooser per project, binding the session to the revision, and one send at a time per revision. */
internal class WebDelivery(
    private val service: WebReviewService,
    private val enqueue: SessionEnqueue,
    private val startSession: SessionStarter,
    private val live: () -> LiveSessions,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
    private val teamOptions: (projectId: Long) -> TeamOptions = { TeamOptions.NONE },
) {
    private val targets = mutableStateMapOf<Long, WebSendTarget>()
    private var sending by mutableStateOf(emptySet<Long>())

    fun sendTarget(projectId: Long): WebSendTarget? = targets[projectId]

    fun isSending(revisionId: Long): Boolean = revisionId in sending

    fun send(revision: WebRevision) = launchOnce(revision) {
        val bound = revision.astrolabeSessionId()
        if (bound == null) offerTargets(revision) else deliver(revision, bound)
    }

    fun sendTo(revision: WebRevision, astrolabeSessionId: String?, team: Team? = null) = launchOnce(revision) {
        targets.remove(revision.projectId())
        deliver(revision, astrolabeSessionId, team)
    }

    fun dismissSendTarget(projectId: Long) {
        targets.remove(projectId)
    }

    private fun launchOnce(revision: WebRevision, work: suspend () -> Unit) {
        val revisionId = revision.id()
        if (!begin(revisionId)) return
        scope.launch {
            try {
                runGuarded(reportError) { work() }
            } finally {
                end(revisionId)
            }
        }
    }

    @Synchronized
    private fun begin(revisionId: Long): Boolean {
        if (revisionId in sending) return false
        sending = sending + revisionId
        return true
    }

    @Synchronized
    private fun end(revisionId: Long) {
        sending = sending - revisionId
    }

    private fun offerTargets(revision: WebRevision) {
        val candidates = live().of(revision.projectId()).map { SessionChoice(it.astrolabeSessionId, it.title?.takeIf(String::isNotBlank) ?: it.astrolabeSessionId) }
        targets[revision.projectId()] = WebSendTarget(revision, candidates, teamOptions(revision.projectId()))
    }

    /** A dead target reopens the chooser before anything is marked sent; the chosen or started session is bound to the revision. */
    private suspend fun deliver(revision: WebRevision, astrolabeSessionId: String?, team: Team? = null) {
        if (astrolabeSessionId != null && live().find(astrolabeSessionId) == null) {
            offerTargets(revision)
            return
        }
        val feedback = io { service.prepareFeedback(revision.id()) }
        if (astrolabeSessionId != null) {
            io { service.bindSession(revision.id(), astrolabeSessionId) }
            enqueue(astrolabeSessionId, feedback.prompt())
        } else {
            startSession(revision.projectId(), team, feedback.prompt(), { reportError("$START_FAILED. Feedback is in ${feedback.markdown()}") }, { started ->
                scope.launch { runGuarded(reportError) { io { service.bindSession(revision.id(), started) } } }
            })
        }
    }
}
