package com.github.krpkbhdn.ccmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.ccmanager.project.TrustLevel
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.Pressable
import com.github.krpkbhdn.ccmanager.ui.components.RadioMark
import com.github.krpkbhdn.ccmanager.ui.theme.Radius
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors
import com.github.krpkbhdn.ccmanager.ui.trust.TRUST_HINT
import com.github.krpkbhdn.ccmanager.ui.trust.TrustState
import com.github.krpkbhdn.ccmanager.ui.trust.trustDescription
import com.github.krpkbhdn.ccmanager.ui.trust.trustLabel

@Composable
fun TrustSettingsPage(state: TrustState) {
    SettingGroup("Default trust level") {
        Label("How much Claude may do without asking in projects that have no level of their own. Autonomous is set per project only. $TRUST_HINT Groups and the Assistant always ask.", color = colors.textSecondary, style = Type.small)
        TrustLevel.entries.filter { it != TrustLevel.AUTONOMOUS }.forEach { level -> LevelChoice(level, state.defaultLevel == level) { state.setDefault(level) } }
    }
}

@Composable
private fun LevelChoice(level: TrustLevel, selected: Boolean, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.md)) {
        Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RadioMark(selected)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Label(trustLabel(level), style = Type.bodyMedium)
                Label(trustDescription(level), color = colors.textSecondary, style = Type.small)
            }
        }
    }
}
