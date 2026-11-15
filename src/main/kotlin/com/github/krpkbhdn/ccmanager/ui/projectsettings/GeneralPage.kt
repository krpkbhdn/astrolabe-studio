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
import com.github.krpkbhdn.ccmanager.ui.components.MenuEntry
import com.github.krpkbhdn.ccmanager.ui.components.PopupMenu
import com.github.krpkbhdn.ccmanager.ui.icons.Icons
import com.github.krpkbhdn.ccmanager.ui.screens.ModelControl
import com.github.krpkbhdn.ccmanager.ui.settings.SettingGroup
import com.github.krpkbhdn.ccmanager.ui.settings.SettingRow
import com.github.krpkbhdn.ccmanager.ui.state.AppState
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing

/** Model, default team and preset of the project; each reuses the control that edits it elsewhere in the app. */
@Composable
fun GeneralPage(app: AppState, projectId: Long) {
    val project = app.projects.find(projectId) ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SettingGroup("Model") {
            SettingRow("Model and effort", "Used by sessions you start next in this project.") { ModelControl(app, project, null) }
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
