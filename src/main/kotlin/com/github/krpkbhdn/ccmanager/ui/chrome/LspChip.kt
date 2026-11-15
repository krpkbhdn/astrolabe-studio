package com.github.krpkbhdn.ccmanager.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.Pressable
import com.github.krpkbhdn.ccmanager.ui.components.TooltipSide
import com.github.krpkbhdn.ccmanager.ui.components.WithTooltip
import com.github.krpkbhdn.ccmanager.ui.projectsettings.LSP_HINT
import com.github.krpkbhdn.ccmanager.ui.projectsettings.LspIndicator
import com.github.krpkbhdn.ccmanager.ui.projectsettings.LspLevel
import com.github.krpkbhdn.ccmanager.ui.theme.Radius
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors

private val dotSize = 6.dp
private val chipHeight = 22.dp

/** Status bar chip: a dot for the health of the project's language servers and "LSP"; details in the tooltip, click opens the Languages settings. */
@Composable
internal fun LspChip(indicator: LspIndicator, onClick: () -> Unit) {
    val description = indicator.lines.joinToString("\n")
    WithTooltip(description, hint = LSP_HINT, side = TooltipSide.ABOVE) {
        Pressable(onClick, Modifier.height(chipHeight).semantics { contentDescription = description }, shape = RoundedCornerShape(Radius.sm + 1.dp), base = Color.Transparent) {
            Row(Modifier.fillMaxHeight().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(dotSize).background(dotColor(indicator.level), CircleShape))
                Label("LSP", color = colors.textSecondary, style = Type.caption)
            }
        }
    }
}

@Composable
private fun dotColor(level: LspLevel): Color = when (level) {
    LspLevel.GREEN -> colors.success
    LspLevel.YELLOW -> colors.warning
    LspLevel.RED -> colors.danger
}
