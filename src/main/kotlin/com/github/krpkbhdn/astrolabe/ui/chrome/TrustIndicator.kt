package com.github.krpkbhdn.astrolabe.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.project.TrustLevel
import com.github.krpkbhdn.astrolabe.ui.components.AnchoredPopup
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.Pressable
import com.github.krpkbhdn.astrolabe.ui.components.RadioMark
import com.github.krpkbhdn.astrolabe.ui.settings.SettingsCategory
import com.github.krpkbhdn.astrolabe.ui.state.AppState
import com.github.krpkbhdn.astrolabe.ui.state.ConfirmRequest
import com.github.krpkbhdn.astrolabe.ui.state.Route
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import com.github.krpkbhdn.astrolabe.ui.trust.TRUST_HINT
import com.github.krpkbhdn.astrolabe.ui.trust.TrustOption
import com.github.krpkbhdn.astrolabe.ui.trust.TrustTone
import com.github.krpkbhdn.astrolabe.ui.trust.trustIndicatorOf

private val panelWidth = 300.dp

/** Status bar chip with the effective trust level of the project on screen; click opens the popup that sets this project's override. */
@Composable
internal fun TrustIndicatorSegment(state: AppState) {
    val route = state.route as? Route.Project
    LaunchedEffect(route?.id) { state.trust.open = false }
    if (route == null) return
    val project = state.projects.find(route.id)
    val trust = state.trust
    val indicator = trustIndicatorOf(project, trust.defaultLevel) ?: return
    Box {
        StatusDotChip(indicator.label, indicator.lines, TRUST_HINT, dotColor(indicator.tone), trust.open) { trust.open = !trust.open }
        if (trust.open && project != null) {
            AnchoredPopup({ trust.open = false }, alignEnd = true, modifier = Modifier.width(panelWidth)) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Label("Trust level of this project", style = Type.bodyMedium)
                    indicator.options.forEach { option -> OptionRow(option) { trust.open = false; choose(state, project, option) } }
                    Label(TRUST_HINT, color = colors.textTertiary, style = Type.small)
                    AppButton("Trust settings", { trust.open = false; state.settingsDialog.show(SettingsCategory.TRUST) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun choose(state: AppState, project: Project, option: TrustOption) {
    if (option.level != TrustLevel.AUTONOMOUS) return state.projects.selectTrust(project, option.level)
    state.confirm = ConfirmRequest(
        title = "Autonomous for ${project.name}",
        message = "Claude will run every tool call without asking (bypassPermissions). The deny rules for secrets and destructive commands are best-effort only. Applies from the next session start.",
        confirmLabel = "Use Autonomous",
    ) { state.projects.selectTrust(project, TrustLevel.AUTONOMOUS) }
}

@Composable
private fun OptionRow(option: TrustOption, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.md)) {
        Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RadioMark(option.checked)
            Label(option.label, style = Type.body)
        }
    }
}

@Composable
private fun dotColor(tone: TrustTone): Color = when (tone) {
    TrustTone.NEUTRAL -> colors.textTertiary
    TrustTone.GOOD -> colors.success
    TrustTone.DANGER -> colors.danger
}
