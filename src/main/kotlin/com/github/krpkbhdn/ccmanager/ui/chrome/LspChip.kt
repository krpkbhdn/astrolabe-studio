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
import com.github.krpkbhdn.ccmanager.ui.components.Glyph
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.Pressable
import com.github.krpkbhdn.ccmanager.ui.components.TooltipSide
import com.github.krpkbhdn.ccmanager.ui.components.WithTooltip
import com.github.krpkbhdn.ccmanager.ui.icons.Icons
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
    StatusDotChip("LSP", indicator.lines, LSP_HINT, dotColor(indicator.level), warning = indicator.warningCount > 0, onClick = onClick)
}

/** A status bar chip: a coloured dot and a label, [lines] and [hint] in the tooltip, a warning triangle after the label when [warning]. */
@Composable
internal fun StatusDotChip(label: String, lines: List<String>, hint: String, dot: Color, active: Boolean = false, warning: Boolean = false, onClick: () -> Unit) {
    val description = lines.joinToString("\n")
    WithTooltip(description, hint = hint, side = TooltipSide.ABOVE) {
        Pressable(onClick, Modifier.height(chipHeight).semantics { contentDescription = description }, shape = RoundedCornerShape(Radius.sm + 1.dp), base = if (active) colors.accentSoft else Color.Transparent) {
            Row(Modifier.fillMaxHeight().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(dotSize).background(dot, CircleShape))
                Label(label, color = colors.textSecondary, style = Type.caption)
                if (warning) Glyph(Icons.Alert, colors.warning, size = 12.dp)
            }
        }
    }
}

@Composable
private fun dotColor(level: LspLevel): Color = when (level) {
    LspLevel.GREEN -> colors.success
    LspLevel.YELLOW -> colors.warning
    LspLevel.RED -> colors.danger
    LspLevel.GRAY -> colors.textTertiary
}
