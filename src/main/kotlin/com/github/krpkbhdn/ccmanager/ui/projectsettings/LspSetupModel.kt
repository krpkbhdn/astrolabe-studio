package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.LspSetupStep
import com.github.krpkbhdn.ccmanager.toolchain.LspSetupStep.Kind
import com.github.krpkbhdn.ccmanager.toolchain.LspSetupStep.Outcome
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.ccmanager.ui.tools.DownloadFact
import com.github.krpkbhdn.ccmanager.ui.tools.downloadFactsOf

/** One confirm entry: what happens for a step, its download facts or command line, or the by-hand note. */
data class LspStepView(val title: String, val facts: List<DownloadFact> = emptyList(), val command: String? = null, val note: String? = null)

fun pendingStepCount(plan: List<LspSetupStep>): Int = plan.count { it.outcome == Outcome.PENDING }

/** The header button label, absent when there is nothing to set up. */
fun setupAllLabelOf(plan: List<LspSetupStep>): String? = pendingStepCount(plan).takeIf { it > 0 }?.let { "Set up all ($it)" }

private fun kindLabel(kind: Kind): String = when (kind) {
    Kind.PREREQUISITE -> "Prerequisite"
    Kind.SERVER -> "Language server"
    Kind.PLUGIN -> "Claude plugin"
}

private const val MAX_SUMMARY_NAMES = 4

private fun namesOf(names: List<String>): String =
    names.take(MAX_SUMMARY_NAMES).joinToString(", ") + (names.size - MAX_SUMMARY_NAMES).takeIf { it > 0 }?.let { ", +$it more" }.orEmpty()

/** Explains the button count: servers and prerequisites first, then the plugins by their real names; pluginOf gives the plugin of a server id. */
fun setupSummaryOf(plan: List<LspSetupStep>, nameOf: (String) -> String?, pluginOf: (String) -> String?): String? {
    fun names(kind: Kind, outcome: Outcome, label: (String) -> String?) =
        plan.filter { it.outcome == outcome && it.kind == kind }.mapNotNull { label(it.toolId) }.distinct()
    if (pendingStepCount(plan) == 0) return null
    val installs = names(Kind.PREREQUISITE, Outcome.PENDING, nameOf) + names(Kind.SERVER, Outcome.PENDING, nameOf)
    val plugins = names(Kind.PLUGIN, Outcome.PENDING) { pluginOf(it)?.substringBefore('@') ?: nameOf(it) ?: it }
    val manual = plan.filter { it.outcome == Outcome.MANUAL }.map { nameOf(it.toolId) ?: it.toolId }.distinct()
    val pluginPart = plugins.takeIf { it.isNotEmpty() }?.let { "${it.size} ${if (it.size == 1) "plugin" else "plugins"}: ${namesOf(it)}" }
    return listOfNotNull(
        listOfNotNull(installs.takeIf { it.isNotEmpty() }?.let(::namesOf), pluginPart).joinToString(" + ").ifEmpty { null },
        manual.takeIf { it.isNotEmpty() }?.let { "manual: ${namesOf(it)}" },
    ).joinToString("; ").ifEmpty { null }
}

fun stepTitleOf(step: LspSetupStep, nameOf: (String) -> String?): String = "${kindLabel(step.kind)}: ${nameOf(step.toolId) ?: step.toolId}"

/** Plugin steps and manual ones carry their own text; an install shows the same facts or command as the single install confirm. */
fun lspConfirmOf(plan: List<LspSetupStep>, planOf: (String) -> InstallPlan?, nameOf: (String) -> String?): List<LspStepView> = plan.map { step ->
    val title = stepTitleOf(step, nameOf)
    val install = planOf(step.toolId)
    when {
        step.outcome == Outcome.MANUAL -> LspStepView(title, note = step.message)
        step.kind == Kind.PLUGIN || install == null -> LspStepView(title, note = step.message)
        install.download().isPresent -> LspStepView(title, facts = downloadFactsOf(install))
        else -> LspStepView(title, command = install.commandLine())
    }
}

fun outcomeLabelOf(step: LspSetupStep): String = when (step.outcome) {
    Outcome.DONE -> "done"
    Outcome.FAILED -> "failed"
    Outcome.SKIPPED -> "skipped"
    Outcome.MANUAL -> "install by hand"
    Outcome.PENDING -> "not run"
}
