package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.github.krpkbhdn.astrolabe.ui.components.AnchoredPopup
import com.github.krpkbhdn.astrolabe.ui.components.Glyph
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.MenuColumn
import com.github.krpkbhdn.astrolabe.ui.components.MenuEntry
import com.github.krpkbhdn.astrolabe.ui.components.Pressable
import com.github.krpkbhdn.astrolabe.ui.components.menuSize
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import kotlinx.coroutines.delay

private const val CLOSE_DELAY_MS = 150L
private val FLYOUT_MAX_HEIGHT = 480.dp
private val CHEVRON_SIZE = 12.dp

/** One row of a [FlyoutMenu]: [label] opens a side list of [entries]. */
data class FlyoutGroup(val label: String, val icon: ImageVector, val entries: List<MenuEntry>)

/** Opens to the right of the anchor row, or to the left when there is no room, aligned to the row's top. */
internal object SideFlyoutPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val right = anchorBounds.right
        val x = if (right + popupContentSize.width <= windowSize.width) right else (anchorBounds.left - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x, anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/** Plain [top] entries, then one row per group; hovering a row (or Right/Enter) opens that group's entries beside it, leaving both closes it, Left closes it. */
@Composable
fun FlyoutMenu(top: List<MenuEntry>, groups: List<FlyoutGroup>) {
    var active by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(Spacing.xs)) {
        MenuColumn(top, onDismiss = {})
        groups.forEach { g -> FlyoutRow(g, g.label == active, { active = g.label }, { if (active == g.label) active = null }) }
    }
}

@Composable
private fun FlyoutRow(group: FlyoutGroup, open: Boolean, show: () -> Unit, hide: () -> Unit) {
    val rowSource = remember { MutableInteractionSource() }
    val flySource = remember { MutableInteractionSource() }
    val rowHovered by rowSource.collectIsHoveredAsState()
    val flyHovered by flySource.collectIsHoveredAsState()
    LaunchedEffect(rowHovered, flyHovered) {
        if (rowHovered) show() else if (!flyHovered) {
            delay(CLOSE_DELAY_MS)
            hide()
        }
    }
    Box(
        Modifier.fillMaxWidth().hoverable(rowSource).onPreviewKeyEvent { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.DirectionRight || e.key == Key.Enter -> { show(); true }
                e.key == Key.DirectionLeft && open -> { hide(); true }
                else -> false
            }
        },
    ) {
        Pressable(show, Modifier.fillMaxWidth(), base = if (open) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.padding(horizontal = Spacing.md - 2.dp, vertical = Spacing.sm - 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md - 2.dp)) {
                Glyph(group.icon, colors.textSecondary)
                Label(group.label, Modifier.weight(1f), color = colors.textPrimary, style = Type.body)
                Glyph(Icons.ChevronRight, colors.textSecondary, size = CHEVRON_SIZE)
            }
        }
        if (open) AnchoredPopup({ hide() }, modifier = Modifier.menuSize().hoverable(flySource), focusable = false, positionProvider = SideFlyoutPosition) {
            MenuColumn(group.entries, maxHeight = FLYOUT_MAX_HEIGHT, onDismiss = {})
        }
    }
}
