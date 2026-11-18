package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.project.Project
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TICK_MILLIS = 1_000L
private const val STALE_REFRESH_TICKS = 30
private const val UNOPENED_GIT_REFRESH_TICKS = 60
private const val RELOAD_MIN_INTERVAL_MILLIS = 150L

/** A group shows the worst status over its own sessions and those of its member projects; one pass over [sessions], not one per project. */
fun projectStatusesOf(sessions: List<SessionSnapshot>, projects: List<Project>): Map<Long, AgentStatus> {
    val worstOf = sessions.groupBy { it.projectId }.mapValues { (_, own) -> own.maxOf { it.status } }
    val membersOf = projects.filter { it.groupId != null }.groupBy { it.groupId }
    return projects.associate { project ->
        val ids = listOf(project.id) + membersOf[project.id].orEmpty().map { it.id }
        project.id to ids.maxOf { worstOf[it] ?: AgentStatus.OFFLINE }
    }
}

/**
 * Keeps [LiveSessions] current: a conflated reload on session changes, and a one-second tick that refreshes the clock, re-reads stale
 * sessions every [STALE_REFRESH_TICKS] ticks and asks for the unopened projects' git sweep. Runs on [scope], the UI dispatcher.
 */
class LiveSessionsSync(
    private val scope: CoroutineScope,
    private val readSessions: () -> List<SessionSnapshot>,
    private val projects: () -> List<Project>,
    private val current: () -> LiveSessions,
    private val publish: (LiveSessions) -> Unit,
    private val afterLoad: (previous: Map<String, AgentStatus>, sessions: List<SessionSnapshot>) -> Unit,
    private val onTick: (now: Instant) -> Unit,
    private val windowActive: () -> Boolean,
    private val sweepUnopenedGits: (refocused: Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    private val reload = Refresher(scope, RELOAD_MIN_INTERVAL_MILLIS) { runGuarded(onError) { load() } }

    fun request() = reload.request()

    fun start() {
        reload.request()
        scope.launch {
            var ticks = 0
            var wasActive = windowActive()
            while (true) {
                delay(TICK_MILLIS)
                onTick(Instant.now())
                if (++ticks % STALE_REFRESH_TICKS == 0) reload.request()
                val active = windowActive()
                val refocused = active && !wasActive
                wasActive = active
                if (active && (refocused || ticks % UNOPENED_GIT_REFRESH_TICKS == 0)) sweepUnopenedGits(refocused)
            }
        }
    }

    private suspend fun load() {
        val registered = projects()
        val (sessions, statuses) = io {
            val all = readSessions()
            all to projectStatusesOf(all, registered)
        }
        val previous = current().sessions.associate { it.astrolabeSessionId to it.status }
        publish(LiveSessions(sessions, statuses))
        afterLoad(previous, sessions)
    }
}
