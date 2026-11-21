package com.github.krpkbhdn.astrolabe.ui.web

/** Toolbar groups that can shrink into one compact control. */
enum class ToolbarItemId { OVERLAYS }

/** A groupable toolbar item: its inline [fullWidth] and the [compactWidth] of its grouped form. */
data class GroupableItem(val id: ToolbarItemId, val fullWidth: Float, val compactWidth: Float)

/** Pure width model of the toolbar: which groupable items switch to their compact form. */
object WebToolbarLayout {
    /** [items] are in priority order, the first is grouped first; [fixedWidth] covers everything that never changes (navigation, address minimum, delivery button, padding). */
    fun compacted(available: Float, fixedWidth: Float, items: List<GroupableItem>): Set<ToolbarItemId> {
        var count = 0
        fun used() = fixedWidth + items.mapIndexed { i, it -> if (i < count) it.compactWidth else it.fullWidth }.sum()
        while (count < items.size && used() > available) count++
        return items.take(count).map { it.id }.toSet()
    }
}
