package com.github.krpkbhdn.astrolabe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.Pressable
import com.github.krpkbhdn.astrolabe.ui.components.RadioMark
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

@Composable
fun CodeModeSettingsPage(state: CodeModeSettingsState) {
    LaunchedEffect(Unit) { state.refreshAll() }
    SettingGroup("About") {
        Label(
            "Code Mode lets Claude call a project's MCP servers by writing a short script instead of loading every tool definition. " +
                "It is switched on per project (Project settings > Code Mode), never globally. The setting below only decides where those scripts run.",
            color = colors.textSecondary, style = Type.small,
        )
    }
    SettingGroup("Isolation environment") {
        if (!state.available) Label("Not available in this run.", color = colors.textSecondary, style = Type.small)
        IsolationBackend.entries.forEach { backend -> BackendChoice(state, backend) }
    }
}

@Composable
private fun BackendChoice(state: CodeModeSettingsState, backend: IsolationBackend) {
    val status = state.statuses[backend]
    val selected = state.selected == backend
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Pressable({ state.select(backend) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.md)) {
            Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                RadioMark(selected)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Label(IsolationView.label(backend), style = Type.bodyMedium)
                    Label(IsolationView.description(backend), color = colors.textSecondary, style = Type.small)
                }
            }
        }
        if (IsolationView.isContainer(backend)) {
            RuntimeDetails(state, backend, status)
            if (selected) IsolationView.warning(backend, status)?.let { Label(it, Modifier.padding(start = 40.dp), color = colors.warning, style = Type.small) }
        }
    }
}

@Composable
private fun RuntimeDetails(state: CodeModeSettingsState, backend: IsolationBackend, status: RuntimeStatus?) {
    Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Label(IsolationView.detection(status), color = colors.textSecondary, style = Type.small)
            AppButton("Refresh", { state.refresh(backend) }, enabled = status != null)
        }
        IsolationView.image(status, state.image)?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Label(text, color = colors.textSecondary, style = Type.small)
                if (status?.imagePresent == false) {
                    val pulling = state.pulling == backend
                    AppButton(if (pulling) "Pulling..." else "Pull image", { state.pullImage(backend) }, enabled = IsolationView.canPull(status, state.pulling != null), loading = pulling)
                }
            }
        }
        if (state.pullDone == backend) Label("Image pulled.", color = colors.textSecondary, style = Type.small)
        state.pullFailure?.takeIf { state.pulling == null && state.selected == backend }?.let { Label(it, color = colors.warning, style = Type.small) }
    }
}
