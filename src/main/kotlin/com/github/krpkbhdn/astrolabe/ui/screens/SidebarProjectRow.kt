package com.github.krpkbhdn.astrolabe.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.ui.components.ContextMenuArea
import com.github.krpkbhdn.astrolabe.ui.components.CountBadge
import com.github.krpkbhdn.astrolabe.ui.components.IconButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.MenuEntry
import com.github.krpkbhdn.astrolabe.ui.components.PopupMenu
import com.github.krpkbhdn.astrolabe.ui.components.Pressable
import com.github.krpkbhdn.astrolabe.ui.components.ProjectAvatar
import com.github.krpkbhdn.astrolabe.ui.components.WithTooltip
import com.github.krpkbhdn.astrolabe.ui.git.GitSummaryLine
import com.github.krpkbhdn.astrolabe.ui.git.model.GitSummary
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.state.AppState
import com.github.krpkbhdn.astrolabe.ui.state.RenameRequest
import com.github.krpkbhdn.astrolabe.ui.state.Route
import com.github.krpkbhdn.astrolabe.ui.state.mostUrgent
import com.github.krpkbhdn.astrolabe.ui.state.pathTail
import com.github.krpkbhdn.astrolabe.ui.theme.Motion
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private val rowHeight = 46.dp
private val markerWidth = 3.dp
private val markerHeight = 20.dp
private val avatarSize = 30.dp

class ProjectActions(
    val onOpen: () -> Unit,
    val onStartTask: () -> Unit,
    val onRename: () -> Unit,
    val onRemove: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onSettings: () -> Unit,
    val onApplyPreset: () -> Unit,
)

fun AppState.projectActions(project: Project) = ProjectActions(
    onOpen = { navigate(Route.Project(project.id)) },
    onStartTask = { tasks.open(project.id) },
    onRename = { projects.renaming = RenameRequest(project) },
    onRemove = { projects.requestRemove(project) },
    onOpenFolder = { environment.openFolder(project) },
    onSettings = { extensions.openProjectSettings(project) },
    onApplyPreset = { applyPreset.open(project.id) },
)

fun projectMenu(actions: ProjectActions): List<MenuEntry> = listOf(
    MenuEntry("Start task with team", Icons.Users) { actions.onStartTask() },
    MenuEntry("Open folder", Icons.FolderOpen) { actions.onOpenFolder() },
    MenuEntry("Project settings", Icons.Settings) { actions.onSettings() },
    MenuEntry("Apply preset...", Icons.Tasks) { actions.onApplyPreset() },
    MenuEntry("Rename", Icons.Edit) { actions.onRename() },
    MenuEntry("Remove from manager", Icons.Trash, danger = true) { actions.onRemove() },
)

/** Sidebar entry of one project: avatar with status dot, name, live activity ticker and a context menu. */
@Composable
fun ProjectRow(
    project: Project,
    status: AgentStatus,
    sessions: List<SessionSnapshot>,
    selected: Boolean,
    collapsed: Boolean,
    actions: ProjectActions,
    modifier: Modifier = Modifier,
    gitSummary: GitSummary? = null,
    groupName: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val marker by animateFloatAsState(if (selected) 1f else 0f, Motion.base(), label = "rowMarker")
    ContextMenuArea({ projectMenu(actions) }, modifier) {
        Box {
            WithTooltip(if (collapsed) listOfNotNull(project.name, groupName?.let { "in $it" }).joinToString(" - ") else null) {
                Pressable(
                    actions.onOpen,
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.md),
                    base = if (selected) colors.accentSoft else Color.Transparent,
                ) { hovered ->
                    if (collapsed) CollapsedRow(project, status) else ExpandedRow(project, status, sessions, gitSummary, hovered, selected) { menuOpen = true }
                }
            }
            Box(
                Modifier.align(Alignment.CenterStart).width(markerWidth).height(markerHeight * marker)
                    .background(colors.accent, RoundedCornerShape(markerWidth)),
            )
            if (menuOpen) {
                PopupMenu(projectMenu(actions), onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
private fun CollapsedRow(project: Project, status: AgentStatus) {
    Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
        ProjectAvatar(project.name, size = avatarSize, status = status)
    }
}

@Composable
private fun ExpandedRow(project: Project, status: AgentStatus, sessions: List<SessionSnapshot>, gitSummary: GitSummary?, hovered: Boolean, selected: Boolean, onMenu: () -> Unit) {
    val ticker = mostUrgent(sessions)?.activity?.takeIf { hovered || selected }
    Row(
        Modifier.height(rowHeight).padding(start = Spacing.md - 2.dp, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md - 2.dp),
    ) {
        ProjectAvatar(project.name, size = avatarSize, status = status)
        Column(Modifier.weight(1f)) {
            Label(project.name, color = colors.textPrimary, style = Type.bodyMedium, tooltipWhenTruncated = true)
            if (ticker != null) {
                Label(ticker, color = colors.status(status), style = Type.caption)
            } else if (gitSummary != null) {
                GitSummaryLine(gitSummary)
            } else {
                Label(pathTail(project.path), color = colors.textTertiary, style = Type.monoSmall.copy(fontSize = Type.caption.fontSize))
            }
        }
        if (hovered) {
            IconButton(Icons.More, onMenu, Modifier.size(24.dp), tooltip = "Project actions", size = 24.dp)
        } else if (sessions.isNotEmpty()) {
            CountBadge(sessions.size, tint = colors.status(status))
        }
    }
}
