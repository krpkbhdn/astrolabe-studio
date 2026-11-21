package com.github.krpkbhdn.astrolabe.ui.web

import com.github.krpkbhdn.astrolabe.web.WebItem
import com.github.krpkbhdn.astrolabe.web.WebItemStatus

/** Whether the pins of an earlier review result (sent or approved) stay in the browser. */
enum class HistoryVisibility(val label: String) {
    SHOW("Keep visible"),
    HIDE("Hidden"),
    UNTIL_NEW_REVIEW("Hide on next feedback"),
}

/** The two choices, one for sent and one for approved feedback. */
data class HistoryModes(
    val sent: HistoryVisibility = HistoryVisibility.UNTIL_NEW_REVIEW,
    val done: HistoryVisibility = HistoryVisibility.HIDE,
)

/** Pins to draw: open items always, dismissed never, sent and approved ones by their mode; [cycleOpen] = the pending revision already has an open item. */
fun List<WebItem>.forPins(modes: HistoryModes, cycleOpen: Boolean): List<WebItem> = filter {
    when (it.status()) {
        WebItemStatus.OPEN -> true
        WebItemStatus.SENT -> modes.sent.shows(cycleOpen)
        WebItemStatus.DONE -> modes.done.shows(cycleOpen)
        WebItemStatus.DISMISSED -> false
    }
}

private fun HistoryVisibility.shows(cycleOpen: Boolean) = when (this) {
    HistoryVisibility.SHOW -> true
    HistoryVisibility.HIDE -> false
    HistoryVisibility.UNTIL_NEW_REVIEW -> !cycleOpen
}
