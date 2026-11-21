package com.github.krpkbhdn.astrolabe.ui.web

import com.github.krpkbhdn.astrolabe.web.ElementAnchor
import com.github.krpkbhdn.astrolabe.web.WebItem
import com.github.krpkbhdn.astrolabe.web.WebItemStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class HistoryVisibilityTest {
    private fun item(id: Long, status: WebItemStatus) =
        WebItem(id, 1, 1, id.toInt(), mock(ElementAnchor::class.java), "c", emptyList(), status, null, null)

    private val all = WebItemStatus.entries.mapIndexed { i, s -> item(i + 1L, s) }
    private fun ids(modes: HistoryModes, cycleOpen: Boolean) = all.forPins(modes, cycleOpen).map { it.status() }

    @Test
    fun `defaults keep sent until new feedback and drop approved`() {
        assertThat(ids(HistoryModes(), false)).containsExactly(WebItemStatus.OPEN, WebItemStatus.SENT)
        assertThat(ids(HistoryModes(), true)).containsExactly(WebItemStatus.OPEN)
    }

    @Test
    fun `show keeps both classes whatever the cycle`() {
        val modes = HistoryModes(HistoryVisibility.SHOW, HistoryVisibility.SHOW)
        assertThat(ids(modes, true)).containsExactly(WebItemStatus.OPEN, WebItemStatus.SENT, WebItemStatus.DONE)
    }

    @Test
    fun `hide drops both classes but never open items`() {
        val modes = HistoryModes(HistoryVisibility.HIDE, HistoryVisibility.HIDE)
        assertThat(ids(modes, false)).containsExactly(WebItemStatus.OPEN)
    }

    @Test
    fun `approved items follow their own mode`() {
        val modes = HistoryModes(HistoryVisibility.HIDE, HistoryVisibility.UNTIL_NEW_REVIEW)
        assertThat(ids(modes, false)).containsExactly(WebItemStatus.OPEN, WebItemStatus.DONE)
        assertThat(ids(modes, true)).containsExactly(WebItemStatus.OPEN)
    }
}
