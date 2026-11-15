package com.github.krpkbhdn.ccmanager.ui.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.project.TrustLevel
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.MenuEntry
import com.github.krpkbhdn.ccmanager.ui.components.PopupMenu
import com.github.krpkbhdn.ccmanager.ui.icons.Icons
import com.github.krpkbhdn.ccmanager.ui.screens.ModelControl
import com.github.krpkbhdn.ccmanager.ui.settings.SettingGroup
import com.github.krpkbhdn.ccmanager.ui.settings.SettingRow
import com.github.krpkbhdn.ccmanager.ui.state.AppState
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors
import com.github.krpkbhdn.ccmanager.ui.trust.AUTONOMOUS_WARNING
import com.github.krpkbhdn.ccmanager.ui.trust.TRUST_HINT
import com.github.krpkbhdn.ccmanager.ui.trust.appDefaultLabel
import com.github.krpkbhdn.ccmanager.ui.trust.trustDescription
import com.github.krpkbhdn.ccmanager.ui.trust.trustIndicatorOf
import com.github.krpkbhdn.ccmanager.ui.trust.trustLabel

/** Model, default team and preset of the project; each reuses the control that edits it elsewhere in the app. */
@Composable
fun GeneralPage(app: AppState, projectId: Long) {
    val project = app.projects.find(projectId) ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SettingGroup("Model") {
            SettingRow("Model and effort", "Used by sessions you start next in this project.") { ModelControl(app, project, null) }
        }
        if (!project.group()) {
            val effective = project.trustOverride() ?: app.trust.defaultLevel
            SettingGroup("Trust level") {
                SettingRow("Permission prompts", "${trustDescription(effective)} $TRUST_HINT") { TrustPicker(app, project) }
                if (effective == TrustLevel.AUTONOMOUS) Label(AUTONOMOUS_WARNING, color = colors.warning, style = Type.small)
            }
        }
        if (app.teams.teams.isNotEmpty()) {
            SettingGroup("Default team") {
                SettingRow("Team for new sessions", "Started with every session you open here, unless you pick another.") { TeamPicker(app, projectId) }
            }
        }
        SettingGroup("Preset") {
            SettingRow("Project preset", "Review what a preset would add to this project, then apply it.") {
                AppButton("Apply preset...", { app.applyPreset.open(projectId) }, icon = Icons.Tasks)
            }
        }
    }
}

@Composable
private fun TrustPicker(app: AppState, project: Project) {
    var open by remember { mutableStateOf(false) }
    val override = project.trustOverride()
    Box {
        AppButton(override?.let(::trustLabel) ?: appDefaultLabel(app.trust.defaultLevel), { open = !open }, icon = Icons.Shield)
        if (open) {
            val entries = trustIndicatorOf(project, app.trust.defaultLevel)?.options.orEmpty()
                .map { option -> MenuEntry(option.label, checked = option.checked) { app.projects.selectTrust(project, option.level) } }
            PopupMenu(entries, { open = false }, alignEnd = true)
        }
    }
}

@Composable
private fun TeamPicker(app: AppState, projectId: Long) {
    var open by remember { mutableStateOf(false) }
    val current = app.teams.defaultTeamOf(projectId)
    Box {
        AppButton(current?.name ?: "Choose a team", { open = !open }, icon = Icons.Users)
        if (open) {
            PopupMenu(app.teams.teams.map { team -> MenuEntry(team.name, checked = team.id == current?.id) { app.teams.setProjectDefault(projectId, team.id) } }, { open = false }, alignEnd = true)
        }
    }
}
