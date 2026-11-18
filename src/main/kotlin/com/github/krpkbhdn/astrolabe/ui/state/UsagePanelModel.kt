package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.usage.RateLimitSnapshot
import com.github.krpkbhdn.astrolabe.usage.RateLimitWindow
import com.github.krpkbhdn.astrolabe.usage.SessionUsage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_PERCENT = 100.0
private val resetFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

/** One limit window of the usage panel; only windows still counting are listed. */
data class UsageWindowDetail(
    val title: String,
    val fraction: Float,
    val level: UsageLevel,
    val usedText: String,
    val remainingText: String,
    val resetsInText: String,
    val resetsAtText: String,
)

/** One live session of the usage panel; figures Claude did not report are null. */
data class SessionUsageRow(
    val astrolabeSessionId: String,
    val title: String,
    val projectName: String,
    val status: AgentStatus,
    val model: String?,
    val effort: String?,
    val contextPercent: Int?,
    val costText: String?,
)

fun usageWindowDetails(snapshot: RateLimitSnapshot?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<UsageWindowDetail> = listOfNotNull(
    snapshot?.fiveHour()?.let { detail("5-hour limit", it, now, zone) },
    snapshot?.sevenDay()?.let { detail("7-day limit", it, now, zone) },
)

/** "updated 3m ago". */
fun usageFreshness(snapshot: RateLimitSnapshot, now: Instant): String = "updated " + relativeTime(snapshot.receivedAt(), now)

fun sessionUsageRows(sessions: List<SessionSnapshot>, usage: Map<String, SessionUsage>, projectName: (Long) -> String): List<SessionUsageRow> =
    byUrgency(sessions).map { session ->
        val figures = usage[session.astrolabeSessionId()]
        SessionUsageRow(
            astrolabeSessionId = session.astrolabeSessionId(),
            title = sessionTitle(session),
            projectName = projectName(session.projectId()),
            status = session.status(),
            model = figures?.modelName() ?: session.model(),
            effort = figures?.effort(),
            contextPercent = figures?.contextUsedPercent()?.roundToInt(),
            costText = figures?.costUsd()?.let(::formatCost),
        )
    }

private fun detail(title: String, window: RateLimitWindow, now: Instant, zone: ZoneId): UsageWindowDetail? {
    if (!window.resetsAt().isAfter(now)) return null
    val used = window.usedPercent().coerceIn(0.0, MAX_PERCENT)
    return UsageWindowDetail(
        title = title,
        fraction = (used / MAX_PERCENT).toFloat(),
        level = usageLevel(used),
        usedText = exactPercent(used) + " used",
        remainingText = exactPercent(MAX_PERCENT - used) + " left",
        resetsInText = resetsInText(window.resetsAt(), now),
        resetsAtText = resetFormat.format(window.resetsAt().atZone(zone)),
    )
}

private const val NO_LIMITS_NOTE = "Usage limits are reported by Claude Code for Pro and Max plans only. Other plans, such as Team, show the per-session figures below."
private const val NO_REPLY_NOTE = "Limits appear after a session of a Pro or Max plan gets its first reply."

/** Top-bar chip text while no limit window is shown; a missing hook helper explains itself, otherwise no session has reported yet. */
fun usageChipEmptyText(unavailableReason: String?): String = if (unavailableReason != null) "Usage unavailable" else "No usage data"

/** Panel explanation while no limit window is shown: the hook helper problem first, then the plan or first-reply hint. */
fun usageEmptyNote(unavailableReason: String?, hasSnapshot: Boolean): String = unavailableReason ?: if (hasSnapshot) NO_REPLY_NOTE else NO_LIMITS_NOTE

fun formatCost(usd: Double): String = "$" + "%.2f".format(Locale.ROOT, usd)
