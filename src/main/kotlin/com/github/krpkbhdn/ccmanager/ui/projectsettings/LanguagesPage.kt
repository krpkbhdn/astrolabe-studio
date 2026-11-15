package com.github.krpkbhdn.ccmanager.ui.projectsettings

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
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.components.AppSwitch
import com.github.krpkbhdn.ccmanager.ui.components.ButtonVariant
import com.github.krpkbhdn.ccmanager.ui.components.Chip
import com.github.krpkbhdn.ccmanager.ui.components.HorizontalRule
import com.github.krpkbhdn.ccmanager.ui.components.TextLink
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.Spinner
import com.github.krpkbhdn.ccmanager.ui.components.WithTooltip
import com.github.krpkbhdn.ccmanager.ui.settings.SettingRow
import com.github.krpkbhdn.ccmanager.ui.settings.tint
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors
import com.github.krpkbhdn.ccmanager.ui.tools.InstallButton
import com.github.krpkbhdn.ccmanager.ui.tools.InstallButtonView
import com.github.krpkbhdn.ccmanager.ui.tools.InstallFlow
import com.github.krpkbhdn.ccmanager.ui.tools.ToolsState
import com.github.krpkbhdn.ccmanager.ui.tools.includesNoteOf
import com.github.krpkbhdn.ccmanager.ui.tools.installButtonOf
import com.github.krpkbhdn.ccmanager.ui.tools.statusChipOf

private val pagePadding = Spacing.xl
private val extrasIndent = Spacing.xl
private const val MARKETPLACE_NOTE = "Enabling adds the official Claude plugin marketplace to your user settings if it is missing."
private const val ENABLE_NOTE = "Enable LSP in this project"

/** Language servers for the project: the Claude Code LSP plugin toggle, server status and install; the install dialog is the shared one of [tools]. */
@Composable
fun LanguagesPage(languages: LanguagesState, tools: ToolsState, setup: LspSetupState, detectedTech: DetectedTechState, project: Project) {
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
    val groups = remember(languages.rows) { languageGroups(languages.rows) }
    var othersOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Label("Claude Code LSP plugins for this project. After a change, restart running sessions.", Modifier.weight(1f), color = colors.textSecondary, style = Type.small, maxLines = 2)
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
        group.rows.forEach { view ->
            LanguageRow(view, languages, tools)
            if (view.extras.isNotEmpty()) ExtrasBlock(view, languages, tools)
        }
    }
}

@Composable
private fun ExtrasBlock(base: LanguageRowView, languages: LanguagesState, tools: ToolsState) {
    var open by remember(base.row.tool.id()) { mutableStateOf(base.extras.any { it.row.detected() }) }
    Column(Modifier.fillMaxWidth().padding(start = extrasIndent), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Label(frameworkExtrasTitle(base), color = colors.textTertiary, style = Type.overline)
            TextLink(if (open) "Hide" else "Show") { open = !open }
        }
        if (open) base.extras.forEach { LanguageRow(it, languages, tools) }
    }
}

@Composable
private fun LanguageRow(view: LanguageRowView, languages: LanguagesState, tools: ToolsState) {
    val tool = view.row.tool
    val id = tool.id()
    val working = languages.isWorking(id)
    val toggle = toggleOf(view, working)
    val shared = sharesBaseServer(view)
    val install = installButtonOf(view.row.status, tools.plans[id], tool.docsUrl(), tools.isManaged(id))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        SettingRow(tool.name(), toggle.note ?: ENABLE_NOTE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (working) Spinner(size = 16.dp)
                pluginChipOf(view)?.let { Chip(it.text, it.tone.tint()) }
                ServerControls(view, install, tools, showStatus = !shared)
                if (!shared) AppSwitch(toggle.checked, { languages.setEnabled(id, it) }, enabled = toggle.enabled)
            }
        }
        includesNoteOf(tool)?.let { Label(it, color = colors.textTertiary, style = Type.caption) }
        val blockedReason = (install as? InstallButton.Install)?.reason?.takeIf { !install.enabled }
        blockedReason?.let { Label(it, color = colors.warning, style = Type.caption, maxLines = 2) }
        installHintOf(view, blockedReason != null)?.let { Label(it, color = colors.warning, style = Type.caption, maxLines = 2) }
        languages.errors[id]?.let { Label(it, color = colors.danger, style = Type.caption, maxLines = 4) }
    }
}

@Composable
private fun ServerControls(view: LanguageRowView, install: InstallButton, tools: ToolsState, showStatus: Boolean) {
    val tool = view.row.tool
    if (showStatus) {
        val chip = statusChipOf(view.row.status, false)
        WithTooltip(chip.tooltip) { Chip(chip.text, chip.tone.tint()) }
    }
    InstallButtonView(install, "Install server", { tools.requestInstall(tool.id()) }, tools::openDocs, { tools.requestUninstall(tool.id()) }, reasonShownElsewhere = true)
}
