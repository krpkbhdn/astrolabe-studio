package com.github.krpkbhdn.astrolabe.ui.settings

import androidx.compose.runtime.Composable
import com.github.krpkbhdn.astrolabe.ui.components.AppSwitch
import com.github.krpkbhdn.astrolabe.ui.state.AppState

@Composable
fun SessionsPage(state: AppState) {
    SettingGroup("Sessions") {
        SettingRow("Group by project", "List the sessions of the Sessions screen under their project.") {
            AppSwitch(state.settings.groupSessionsByProject, state.prefs::setGroupSessionsByProject)
        }
    }
}
