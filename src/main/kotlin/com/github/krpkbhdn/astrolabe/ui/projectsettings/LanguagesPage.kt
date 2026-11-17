package com.github.krpkbhdn.astrolabe.ui.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolRow
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.AppSwitch
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.Chip
import com.github.krpkbhdn.astrolabe.ui.components.HorizontalRule
import com.github.krpkbhdn.astrolabe.ui.components.TextLink
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.Spinner
import com.github.krpkbhdn.astrolabe.ui.components.WithTooltip
import com.github.krpkbhdn.astrolabe.ui.settings.SettingRow
import com.github.krpkbhdn.astrolabe.ui.state.ConfirmRequest
import com.github.krpkbhdn.astrolabe.ui.settings.tint
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import com.github.krpkbhdn.astrolabe.ui.tools.ChannelControls
import com.github.krpkbhdn.astrolabe.ui.tools.InstallButton
import com.github.krpkbhdn.astrolabe.ui.tools.InstallButtonView
import com.github.krpkbhdn.astrolabe.ui.tools.InstallFlow
import com.github.krpkbhdn.astrolabe.ui.tools.ToolsState
import com.github.krpkbhdn.astrolabe.ui.tools.includesNoteOf
import com.github.krpkbhdn.astrolabe.ui.tools.installButtonOf
import com.github.krpkbhdn.astrolabe.ui.tools.statusChipOf

private val pagePadding = Spacing.xl
private const val MARKETPLACE_NOTE = "Enabling adds the plugin marketplace it needs to your user settings if it is missing: the official Claude one, or this app's own local one for CSS / SCSS / Less."
private const val ENABLE_NOTE = "Enable LSP in this project"

/** Language servers for the project: the Claude Code LSP plugin toggle, server status and install; the install dialog is the shared one of [tools]. */
@Composable
fun LanguagesPage(languages: LanguagesState, tools: ToolsState, setup: LspSetupState, detectedTech: DetectedTechState, project: Project, confirm: (ConfirmRequest) -> Unit = {}) {
    LaunchedEffect(Unit) {
        languages.refresh()
        tools.loadPlans()
        setup.loadPlan(project)
    }
    val installDone = tools.install is InstallFlow.Done || tools.install is InstallFlow.Removed
    val setupDone = setup.flow is LspSetupFlow.Done
    LaunchedEffect(installDone, setupDone) {
        if (installDone || setupDone) {
            languages.refreshAfterInstall()
            if (installDone) detectedTech.refresh(project)
            setup.loadPlan(project)
        }
    }
    LaunchedEffect(languages.busy) {
        if (!languages.busy) detectedTech.refresh(project)
    }
    val groups = remember(languages.rows) { languageGroups(languages.rows) }
    var othersOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (project.group) GroupInheritSwitch("language settings", languages.inherits, languages.busy, confirm, { languages.inheritFromMembers(false) }, { languages.inheritFromMembers(true) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Label("Claude Code LSP plugins for this ${if (project.group) "group" else "project"}. After a change, restart running sessions.", Modifier.weight(1f), color = colors.textSecondary, style = Type.small, maxLines = 2)
            setupAllLabelOf(setup.plannedFor(project.id))?.let { AppButton(it, { setup.start(project) }, variant = ButtonVariant.PRIMARY, compact = true) }
            AppButton("Refresh", { languages.refresh(fresh = true); detectedTech.refresh(project) }, enabled = !languages.loading, loading = languages.loading, compact = true)
        }
        setupSummaryOf(setup.plannedFor(project.id), tools::nameOf) { id -> languages.rows.firstOrNull { it.tool.id() == id }?.tool?.lspPlugin() }
            ?.let { Label("Will set up: $it", color = colors.textSecondary, style = Type.small, maxLines = 3) }
        Label(MARKETPLACE_NOTE, color = colors.textTertiary, style = Type.caption, maxLines = 2)
        languages.loadError?.let { Label(it, color = colors.danger, style = Type.small, maxLines = 3) }
        val (detected, others) = groups.partition { it.detected }
        detected.forEach { GroupSection(it, languages, tools) }
        if (others.isNotEmpty()) {
            TextLink(if (othersOpen) "Hide other languages" else "Other languages (${others.size})") { othersOpen = !othersOpen }
            if (othersOpen) others.forEach { GroupSection(it, languages, tools) }
        }
    }
}

@Composable
private fun GroupSection(group: LanguageGroupView, languages: LanguagesState, tools: ToolsState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Label(group.title, color = colors.textTertiary, style = Type.overline)
            if (group.detected) Chip("Detected", colors.accent)
        }
        HorizontalRule()
        group.rows.forEach { LanguageRow(it, languages, tools) }
    }
}

@Composable
private fun LanguageRow(row: ProjectToolRow, languages: LanguagesState, tools: ToolsState) {
    val tool = row.tool
    val id = tool.id()
    val working = languages.isWorking(id)
    val toggle = toggleOf(row, working, languages.inherits)
    val install = installButtonOf(row.status, tools.plans[id], tool.docsUrl(), tools.isManaged(id))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        SettingRow(tool.name(), toggle.note ?: ENABLE_NOTE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (working) Spinner(size = 16.dp)
                pluginChipOf(row)?.let { Chip(it.text, it.tone.tint()) }
                ServerControls(row, install, tools)
                AppSwitch(toggle.checked, { languages.setEnabled(id, it) }, enabled = toggle.enabled)
            }
        }
        if (languages.inherits) sourceNoteOf(languages.sources[id])?.let { Label(it, color = colors.textSecondary, style = Type.caption) }
        includesNoteOf(tool)?.let { Label(it, color = colors.textTertiary, style = Type.caption) }
        val blockedReason = (install as? InstallButton.Install)?.reason?.takeIf { !install.enabled }
        blockedReason?.let { Label(it, color = colors.warning, style = Type.caption, maxLines = 2) }
        installHintOf(row, blockedReason != null)?.let { Label(it, color = colors.warning, style = Type.caption, maxLines = 2) }
        languages.errors[id]?.let { Label(it, color = colors.danger, style = Type.caption, maxLines = 4) }
    }
}

@Composable
private fun ServerControls(row: ProjectToolRow, install: InstallButton, tools: ToolsState) {
    val tool = row.tool
    val chip = statusChipOf(row.status, false)
    WithTooltip(chip.tooltip) { Chip(chip.text, chip.tone.tint()) }
    ChannelControls(tool, row.status, tools)
    InstallButtonView(install, "Install server", { tools.requestInstall(tool.id()) }, tools::openDocs, { tools.requestUninstall(tool.id()) }, reasonShownElsewhere = true)
}
