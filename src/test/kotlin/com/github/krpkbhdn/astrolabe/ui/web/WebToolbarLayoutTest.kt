package com.github.krpkbhdn.astrolabe.ui.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebToolbarLayoutTest {
    private val overlays = GroupableItem(ToolbarItemId.OVERLAYS, 200f, 30f)

    @Test
    fun wide_width_groups_nothing() {
        assertThat(WebToolbarLayout.compacted(2000f, 300f, listOf(overlays))).isEmpty()
    }

    @Test
    fun exact_fit_stays_inline() {
        assertThat(WebToolbarLayout.compacted(500f, 300f, listOf(overlays))).isEmpty()
    }

    @Test
    fun one_unit_short_groups_the_item() {
        assertThat(WebToolbarLayout.compacted(499f, 300f, listOf(overlays))).containsExactly(ToolbarItemId.OVERLAYS)
    }

    @Test
    fun items_are_grouped_in_priority_order_and_only_as_needed() {
        val first = GroupableItem(ToolbarItemId.OVERLAYS, 100f, 10f)
        val second = GroupableItem(ToolbarItemId.OVERLAYS, 100f, 10f).copy()
        assertThat(WebToolbarLayout.compacted(250f, 100f, listOf(first, second))).hasSize(1)
    }

    @Test
    fun nothing_registered_groups_nothing() {
        assertThat(WebToolbarLayout.compacted(10f, 300f, emptyList())).isEmpty()
    }
}
