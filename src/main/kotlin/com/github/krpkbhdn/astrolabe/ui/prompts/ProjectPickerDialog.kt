package com.github.krpkbhdn.astrolabe.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.team.Team
import com.github.krpkbhdn.astrolabe.ui.components.Chip
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private const val NO_PROJECTS_HINT = "Add a project first, then start prompts in it."

/** "Choose a project": cards with name, path and default team; [teamOf] gives a project's default team. */
@Composable
fun ProjectPickerDialog(projects: List<Project>, teamOf: (Project) -> Team?, onChoose: (Project) -> Unit, onDismiss: () -> Unit) {
    PickerDialog("Choose a project", Icons.Folder, projects, NO_PROJECTS_HINT, onChoose, onDismiss) { project, selected, onHover ->
        PickerCard(selected, onHover, { onChoose(project) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Label(project.name, Modifier.weight(1f, fill = false), style = Type.bodyMedium, tooltipWhenTruncated = true)
                teamOf(project)?.let { Chip(it.name, colors.textSecondary) }
            }
            Label(project.path.toString(), color = colors.textTertiary, style = Type.caption, tooltipWhenTruncated = true)
        }
    }
}
