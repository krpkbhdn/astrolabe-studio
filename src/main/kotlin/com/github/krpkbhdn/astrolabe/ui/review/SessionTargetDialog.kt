package com.github.krpkbhdn.astrolabe.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.team.Team
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.teams.TeamSelect
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.colors

/** Teams offered when a review starts a new session, and the one preselected (the project's default). */
data class TeamOptions(val teams: List<Team>, val preselected: Team?) {
    companion object {
        val NONE = TeamOptions(emptyList(), null)
    }
}

/** Chooses which live session of the project receives a review's feedback; null session from [onPick] means a new one started with the chosen team. */
@Composable
fun SessionTargetDialog(
    candidates: List<SessionChoice>,
    onPick: (session: String?, team: Team?) -> Unit,
    onDismiss: () -> Unit,
    teams: TeamOptions = TeamOptions.NONE,
) {
    var team by remember { mutableStateOf(teams.preselected) }
    ModalHost(onDismiss) {
        DialogBody("Send to agent", Icons.Send, colors.accent) {
            if (candidates.isNotEmpty()) Label("Send to a running session", color = colors.textSecondary)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                candidates.forEach { AppButton(it.label, { onPick(it.astrolabeSessionId, null) }, Modifier.fillMaxWidth(), icon = Icons.Bot) }
                if (teams.teams.isNotEmpty()) {
                    Label("Team for a new session", color = colors.textSecondary)
                    TeamSelect(team, teams.teams) { team = it }
                }
                AppButton("New session", { onPick(null, team) }, Modifier.fillMaxWidth(), variant = ButtonVariant.PRIMARY, icon = Icons.Plus)
                AppButton("Cancel", onDismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.GHOST)
            }
        }
    }
}
