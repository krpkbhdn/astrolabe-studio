package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.notification.NotificationKind

private const val LISTED_PROJECTS = 3

/** One alert waiting for the batching window to end; [title] already names what is asked (Question, Plan, ...). */
data class OsAlert(val kind: NotificationKind, val projectId: Long, val projectName: String, val astrolabeSessionId: String?, val title: String, val body: String)

/** What the popup shows; no [astrolabeSessionId] and no [projectId] means a summary of several sessions. */
data class OsNotice(val kind: NotificationKind, val title: String, val body: String, val astrolabeSessionId: String?, val projectId: Long?) {
    val summary: Boolean get() = astrolabeSessionId == null && projectId == null
}

fun isEnabled(settings: UiSettings, kind: NotificationKind): Boolean = when (kind) {
    NotificationKind.NEEDS_ACTION, NotificationKind.USAGE_ALERT -> settings.osNotifyNeedsAction
    NotificationKind.FINISHED -> settings.osNotifyFinished
    NotificationKind.ERROR -> settings.osNotifyError
    NotificationKind.INFO -> false
}

/** An active window is covered by the in-app toast, so the desktop popup is only for a window the user is not looking at. */
fun shouldNotify(settings: UiSettings, kind: NotificationKind, windowActive: Boolean): Boolean =
    !settings.doNotDisturb && isEnabled(settings, kind) && !windowActive

fun summarize(alerts: List<OsAlert>): OsNotice {
    val only = alerts.singleOrNull()
    if (only != null) return OsNotice(only.kind, "${only.projectName}: ${only.title}", only.body, only.astrolabeSessionId, only.projectId)
    val anyWaits = alerts.any { it.kind == NotificationKind.NEEDS_ACTION }
    val title = if (alerts.all { it.kind == NotificationKind.NEEDS_ACTION }) "${alerts.size} sessions need your action" else "${alerts.size} sessions need attention"
    val projects = alerts.map { it.projectName }.distinct()
    val listed = projects.take(LISTED_PROJECTS).joinToString(", ")
    val body = if (projects.size > LISTED_PROJECTS) "$listed and ${projects.size - LISTED_PROJECTS} more" else listed
    return OsNotice(if (anyWaits) NotificationKind.NEEDS_ACTION else alerts.first().kind, title, body, null, null)
}
