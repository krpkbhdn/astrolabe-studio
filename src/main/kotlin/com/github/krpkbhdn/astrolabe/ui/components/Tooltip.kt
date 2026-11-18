package com.github.krpkbhdn.astrolabe.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import androidx.compose.foundation.background

private const val TOOLTIP_DELAY_MILLIS = 450
private val tooltipGap = 8.dp
private val tooltipMaxWidth = 320.dp
private val tooltipElevation = 8.dp

/** Where the tooltip sits relative to its component; the status bar uses [ABOVE] so the tooltip never covers the item. */
@OptIn(ExperimentalFoundationApi::class)
enum class TooltipSide(private val edge: Alignment, private val gapSign: Int) {
    BELOW(Alignment.BottomCenter, 1),
    ABOVE(Alignment.TopCenter, -1);

    internal fun placement() = TooltipPlacement.ComponentRect(
        anchor = edge,
        alignment = edge,
        offset = DpOffset(0.dp, tooltipGap * gapSign),
    )
}

/** Wraps [content] in a hover tooltip showing [text] (and an optional shortcut [hint]); no tooltip when [text] is null. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTooltip(text: String?, modifier: Modifier = Modifier, hint: String? = null, side: TooltipSide = TooltipSide.BELOW, content: @Composable () -> Unit) {
    if (text == null) return content()
    TooltipArea(
        tooltip = { TooltipBubble(text, hint) },
        modifier = modifier,
        delayMillis = TOOLTIP_DELAY_MILLIS,
        tooltipPlacement = side.placement(),
        content = content,
    )
}

@Composable
private fun TooltipBubble(text: String, hint: String?) {
    val shape = RoundedCornerShape(Radius.sm)
    Row(
        Modifier.shadow(tooltipElevation, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.borderStrong, shape)
            .widthIn(max = tooltipMaxWidth)
            .padding(horizontal = Spacing.sm + 2.dp, vertical = Spacing.xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Label(text, style = Type.small, maxLines = 4)
        hint?.let { Label(it, color = colors.textTertiary, style = Type.monoSmall) }
    }
}
