package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.proposal.Proposal
import com.github.krpkbhdn.astrolabe.notification.AppNotification
import com.github.krpkbhdn.astrolabe.notification.NotificationKind
import com.github.krpkbhdn.astrolabe.ui.proposal.proposalHeadline
import com.github.krpkbhdn.astrolabe.ui.proposal.proposalKindLabel
import kotlinx.coroutines.CoroutineScope

private const val BODY_LIMIT = 80

/** Desktop popups of the app: describes alerts, naming the kind of question for a waiting session. */
class OsNotifications(
    scope: CoroutineScope,
    settings: () -> UiSettings,
    windowActive: () -> Boolean,
    isWaiting: (String) -> Boolean,
    private val proposalOf: (String) -> Proposal?,
    private val projectName: (Long) -> String,
    show: (OsNotice) -> Unit,
) {
    private val notifier = OsNotifier(scope, settings, windowActive, isWaiting, ::alertOf, show)

    fun offer(notification: AppNotification) = notifier.offer(notification)

    private fun alertOf(notification: AppNotification): OsAlert {
        val sessionId = notification.astrolabeSessionId
        val proposal = sessionId?.takeIf { notification.kind == NotificationKind.NEEDS_ACTION }?.let(proposalOf)
        val title = proposal?.let { "${proposalKindLabel(it)} needs your answer" } ?: notification.title
        val body = proposal?.let { proposalHeadline(it).truncated() } ?: notification.body
        return OsAlert(notification.kind, notification.projectId, ownerName(notification), sessionId, title, body)
    }

    private fun ownerName(notification: AppNotification) =
        if (notification.kind == NotificationKind.USAGE_ALERT) "Usage" else projectName(notification.projectId)

    private fun String.truncated(): String = if (length > BODY_LIMIT) take(BODY_LIMIT - 1) + "…" else this
}
