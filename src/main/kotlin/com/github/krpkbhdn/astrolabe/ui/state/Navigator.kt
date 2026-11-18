package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher
import com.github.krpkbhdn.astrolabe.notification.AppNotification
import com.github.krpkbhdn.astrolabe.notification.NotificationKind
import com.github.krpkbhdn.astrolabe.ui.sessions.SessionsTab

/** The current screen and the ways to reach one; what else closes on a screen change is [onNavigate]'s business. */
class Navigator(
    private val live: () -> LiveSessions,
    private val showSessionTab: (projectId: Long, astrolabeSessionId: String) -> Unit,
    private val onNavigate: (Route) -> Unit,
    private val answer: (astrolabeSessionId: String) -> Unit,
    private val answerNextWaiting: () -> Unit,
    private val dismissToast: (Long) -> Unit,
) {
    var route by mutableStateOf<Route>(Route.Overview)
        private set
    var sessionsTab by mutableStateOf(SessionsTab.ACTIVE)
        private set
    var searchFocusRequests by mutableIntStateOf(0)
        private set

    fun navigate(target: Route) {
        route = target
        onNavigate(target)
    }

    /** Falls back to the overview when the current project screen is one for which [isGone] is true. */
    fun leaveProjectIf(isGone: (projectId: Long) -> Boolean) {
        (route as? Route.Project)?.takeIf { isGone(it.id) }?.let { route = Route.Overview }
    }

    fun openSessions(tab: SessionsTab) {
        sessionsTab = tab
        if (tab == SessionsTab.HISTORY) searchFocusRequests++
        navigate(Route.Sessions)
    }

    fun selectSessionsTab(tab: SessionsTab) {
        sessionsTab = tab
    }

    fun openSession(astrolabeSessionId: String) {
        val session = live().find(astrolabeSessionId) ?: return
        showSessionTab(session.projectId, astrolabeSessionId)
        navigate(routeOf(session.projectId))
    }

    fun routeOf(projectId: Long): Route =
        if (projectId == ClaudeLauncher.ASSISTANT_PROJECT_ID) Route.Assistant else Route.Project(projectId)

    fun openNotification(target: AppNotification) {
        val session = target.astrolabeSessionId?.let { live().find(it) }
        when {
            target.kind == NotificationKind.USAGE_ALERT -> navigate(Route.Usage)
            session != null -> openSession(session.astrolabeSessionId)
            else -> navigate(routeOf(target.projectId))
        }
    }

    fun openToast(toast: ToastItem) {
        dismissToast(toast.id)
        val session = toast.astrolabeSessionId?.let { live().find(it) }
        when {
            session != null && toast.kind == NotificationKind.NEEDS_ACTION -> answer(session.astrolabeSessionId)
            toast.kind == NotificationKind.USAGE_ALERT -> navigate(Route.Usage)
            session != null -> openSession(session.astrolabeSessionId)
            toast.projectId != null -> navigate(routeOf(toast.projectId))
            toast.summary && toast.kind == NotificationKind.NEEDS_ACTION -> answerNextWaiting()
            toast.summary -> navigate(Route.Sessions)
        }
    }
}
