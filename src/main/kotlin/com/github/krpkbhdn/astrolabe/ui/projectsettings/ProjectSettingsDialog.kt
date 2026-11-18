package com.github.krpkbhdn.astrolabe.ui.projectsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.components.CountBadge
import com.github.krpkbhdn.astrolabe.ui.components.Glyph
import com.github.krpkbhdn.astrolabe.ui.components.HorizontalRule
import com.github.krpkbhdn.astrolabe.ui.components.IconButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.components.Pressable
import com.github.krpkbhdn.astrolabe.ui.components.VerticalRule
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.McpPage
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.mcpGroups
import com.github.krpkbhdn.astrolabe.ui.extensions.plugins.PluginCommandDialog
import com.github.krpkbhdn.astrolabe.ui.extensions.plugins.PluginsPage
import com.github.krpkbhdn.astrolabe.ui.extensions.plugins.installedEntries
import com.github.krpkbhdn.astrolabe.ui.extensions.skills.SkillsPage
import com.github.krpkbhdn.astrolabe.ui.extensions.skills.skillGroups
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.state.AppState
import com.github.krpkbhdn.astrolabe.ui.theme.IconSize
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import com.github.krpkbhdn.astrolabe.ui.tools.InstallFlow

private const val WINDOW_SHARE = 0.88f
private val navWidth = 216.dp

private fun ProjectSettingsSection.icon(): ImageVector = when (this) {
    ProjectSettingsSection.GENERAL -> Icons.Settings
    ProjectSettingsSection.LANGUAGES -> Icons.Code
    ProjectSettingsSection.PLUGINS -> Icons.Plug
    ProjectSettingsSection.MCP -> Icons.Server
    ProjectSettingsSection.SKILLS -> Icons.Book
    ProjectSettingsSection.CODE_MODE -> Icons.Code
}

/** IDE-style settings of one project: a panel of sections on the left, the page of the selected one on the right. */
@Composable
fun ProjectSettingsDialog(state: ProjectSettingsState, app: AppState, onClose: () -> Unit) {
    val innerDialogOpen = state.innerDialogOpen || app.tools.install != InstallFlow.Idle || app.applyPreset.isOpen
    BoxWithConstraints {
        ModalHost({ if (!innerDialogOpen) onClose() }, width = maxWidth * WINDOW_SHARE, escapeDismisses = !innerDialogOpen) {
            Column(Modifier.height(maxHeight * WINDOW_SHARE)) {
                SettingsHeader(state.context.name, state.group, onClose)
                HorizontalRule()
                Row(Modifier.weight(1f)) {
                    SectionPanel(state)
                    VerticalRule()
                    Box(Modifier.weight(1f).fillMaxHeight()) { SectionPage(state, app) }
                }
            }
        }
        state.plugins.pending?.let { PluginCommandDialog(it, state.plugins.job == null, state.plugins::confirmPending, state.plugins::dismissPending) }
    }
}

@Composable
private fun SettingsHeader(name: String, group: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.md, top = Spacing.md, bottom = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Glyph(Icons.Settings, colors.textSecondary, size = IconSize.large)
        Label((if (group) "Group settings" else "Project settings") + " — $name", Modifier.weight(1f), style = Type.subtitle, tooltipWhenTruncated = true)
        IconButton(Icons.Close, onClose, tooltip = "Close", hint = "Esc")
    }
}

@Composable
private fun SectionPanel(state: ProjectSettingsState) {
    Column(Modifier.width(navWidth).fillMaxHeight().background(colors.sidebar).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        sectionsOf(state.group).forEach { section ->
            SectionRow(section, state.section == section, sectionCount(state, section)) { state.section = section }
        }
    }
}

@Composable
private fun sectionCount(state: ProjectSettingsState, section: ProjectSettingsSection): Int {
    val context = state.context
    return when (section) {
        ProjectSettingsSection.GENERAL -> 0
        ProjectSettingsSection.LANGUAGES -> remember(state.languages.rows) { state.languages.rows.count { it.detected() } }
        ProjectSettingsSection.PLUGINS -> remember(state.plugins.installed) { installedEntries(state.plugins.installed, context).size }
        ProjectSettingsSection.MCP -> remember(state.mcp.servers, state.mcp.health) { mcpGroups(state.mcp.servers, state.mcp.health, context).size }
        ProjectSettingsSection.SKILLS -> remember(state.skills.skills) { skillGroups(state.skills.skills, context).size }
        ProjectSettingsSection.CODE_MODE -> 0
    }
}

@Composable
private fun SectionRow(section: ProjectSettingsSection, selected: Boolean, count: Int, onClick: () -> Unit) {
    val tint: Color = if (selected) colors.accent else colors.textSecondary
    Pressable(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.md), base = if (selected) colors.accentSoft else Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Glyph(section.icon(), tint, size = IconSize.large)
            Label(section.label, Modifier.weight(1f), color = if (selected) colors.textPrimary else colors.textSecondary, style = Type.bodyMedium)
            if (count > 0) CountBadge(count)
        }
    }
}

@Composable
private fun SectionPage(state: ProjectSettingsState, app: AppState) {
    when (state.section) {
        ProjectSettingsSection.GENERAL -> GeneralPage(app, state.context.id)
        ProjectSettingsSection.LANGUAGES -> app.projects.all.firstOrNull { it.id == state.context.id }?.let { LanguagesPage(state.languages, app.tools, app.lspSetup, app.detectedTech, it) { request -> app.confirm = request } }
        ProjectSettingsSection.PLUGINS -> PluginsPage(state.context, state.plugins)
        ProjectSettingsSection.MCP -> McpPage(state.context, state.mcp)
        ProjectSettingsSection.SKILLS -> SkillsPage(state.context, state.skills)
        ProjectSettingsSection.CODE_MODE -> CodeModePage(state.codeMode, state.mcp, state.group) { request -> app.confirm = request }
    }
}
