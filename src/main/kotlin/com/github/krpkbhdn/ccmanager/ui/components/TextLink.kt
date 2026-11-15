package com.github.krpkbhdn.ccmanager.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.ccmanager.ui.theme.Radius
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors

/** Accent-coloured text that acts as a link or a small inline action. */
@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    Pressable(onClick, shape = RoundedCornerShape(Radius.sm), hover = colors.accentSoft) {
        Label(text, Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs), color = colors.accent, style = Type.bodyMedium)
    }
}
