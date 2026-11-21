package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.ui.components.AnchoredPopup
import com.github.krpkbhdn.astrolabe.ui.components.IconButton
import com.github.krpkbhdn.astrolabe.ui.components.MenuColumn
import com.github.krpkbhdn.astrolabe.ui.components.MenuEntry
import com.github.krpkbhdn.astrolabe.ui.components.menuSize
import com.github.krpkbhdn.astrolabe.ui.icons.Icons

private const val TOOLTIP = "Earlier feedback on the page - choose whether the pins of sent and approved feedback stay visible"

/** Toolbar button with a menu that sets, apart for sent and approved feedback, whether its pins stay in the browser. */
@Composable
fun WebHistoryMenu(modes: HistoryModes, onChange: (HistoryModes) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sentChoice = HistoryVisibility.entries.map { mode -> mode to { onChange(modes.copy(sent = mode)) } }
    val doneChoice = HistoryVisibility.entries.map { mode -> mode to { onChange(modes.copy(done = mode)) } }
    Box(Modifier) {
        IconButton(Icons.Eye, { open = !open }, tooltip = TOOLTIP, active = open)
        if (open) AnchoredPopup({ open = false }, modifier = Modifier.menuSize()) {
            MenuColumn(historyEntries("Sent feedback", modes.sent, sentChoice) + historyEntries("Approved feedback", modes.done, doneChoice, divider = true), onDismiss = {})
        }
    }
}

/** A heading and one checked-when-current entry per choice; the menu stays open after a pick. */
internal fun historyEntries(
    title: String,
    current: HistoryVisibility,
    choices: List<Pair<HistoryVisibility, () -> Unit>>,
    divider: Boolean = false,
): List<MenuEntry> =
    listOf(MenuEntry(title, enabled = false, dividerAbove = divider) {}) +
        choices.map { (mode, select) -> MenuEntry(mode.label, checked = mode == current, onSelect = select) }
