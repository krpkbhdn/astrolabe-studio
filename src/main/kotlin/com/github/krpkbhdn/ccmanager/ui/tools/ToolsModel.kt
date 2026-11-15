package com.github.krpkbhdn.ccmanager.ui.tools

import com.github.krpkbhdn.ccmanager.toolchain.LauncherSpec
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.DownloadPlan
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallResult
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.Outcome
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.toolchain.ToolStatus

const val PREREQUISITES_GROUP = "Prerequisites"
const val LOG_LINE_LIMIT = 500
private const val LOG_CHARS_PER_LINE = 200
private const val FOLLOW_TOLERANCE_PX = 4

enum class ChipTone { MUTED, NEUTRAL, SUCCESS, WARNING, DANGER }

data class StatusChipView(val text: String, val tone: ChipTone, val tooltip: String?)

/** One tool with its last probe result and, for a language server, its add-ons nested below. */
data class ToolRow(val tool: ToolDefinition, val status: ToolStatus?, val extras: List<ToolRow> = emptyList())

data class ToolGroup(val title: String, val rows: List<ToolRow>)

sealed interface InstallButton {
    data object Hidden : InstallButton
    /** [websiteUrl] is set only for a tool that has no automatic command: the dialog then shows how to install it by hand. */
    data class Install(val enabled: Boolean, val reason: String?, val websiteUrl: String? = null) : InstallButton
    /** A tool this app downloaded: Update runs the install flow again, Uninstall removes it. */
    data class Manage(val updateEnabled: Boolean, val updateReason: String?) : InstallButton
}

/** What the dialog for a tool without an automatic command shows: the message, the command to copy (null when none is known) and the download page. */
data class ManualInstallView(val message: String, val command: String?, val docsUrl: String?)

/** "Includes React" for a server that covers technologies without a row of their own, null otherwise. */
fun includesNoteOf(tool: ToolDefinition): String? =
    tool.includes().takeIf { it.isNotEmpty() }?.let { "Includes " + it.joinToString(", ") }

fun manualInstallOf(command: String, docsUrl: String): ManualInstallView {
    val url = docsUrl.takeIf { it.isNotBlank() }
    val text = command.takeIf { it.isNotBlank() }
    return when {
        text != null -> ManualInstallView("This package cannot be installed automatically. Run this command in a terminal:", text, url)
        url != null -> ManualInstallView("No command is known for this system. Use the download page:", null, url)
        else -> ManualInstallView("No command is known for this system.", null, null)
    }
}

/** Follow the newest log line unless the user scrolled away from the end. */
fun keepsFollowing(scrollValue: Int, scrollMax: Int, tolerance: Int = FOLLOW_TOLERANCE_PX): Boolean = scrollValue >= scrollMax - tolerance

fun isPresent(status: ToolStatus?): Boolean = status?.state == ToolState.INSTALLED || status?.state == ToolState.RESPONDING

fun statusChipOf(status: ToolStatus?, checking: Boolean): StatusChipView {
    if (checking) return StatusChipView("Checking...", ChipTone.NEUTRAL, null)
    if (status == null) return StatusChipView("Not checked", ChipTone.NEUTRAL, null)
    val withVersion = { label: String -> if (status.version.isBlank()) label else "$label ${status.version}" }
    val tooltip = status.detail.ifBlank { null }
    return when (status.state) {
        ToolState.MISSING -> StatusChipView("Missing", ChipTone.WARNING, tooltip)
        ToolState.INSTALLED -> StatusChipView(withVersion("Installed"), ChipTone.SUCCESS, tooltip)
        ToolState.RESPONDING -> StatusChipView(withVersion("Responding"), ChipTone.SUCCESS, tooltip)
        ToolState.FAILED -> StatusChipView("Failed", ChipTone.DANGER, tooltip)
    }
}

/** Items split into top-level ones and add-ons nested under a base that is in the same list; shared by the Tools page and the project languages. */
class AddOnNesting<T>(val topLevel: List<T>, private val extrasByBase: Map<String, List<T>>, private val definitionOf: (T) -> ToolDefinition) {
    fun extrasOf(item: T): List<T> = extrasByBase[definitionOf(item).id].orEmpty()
}

fun <T> nestAddOns(items: List<T>, definitionOf: (T) -> ToolDefinition): AddOnNesting<T> {
    val ids = items.map { definitionOf(it).id }.toSet()
    val (addOns, topLevel) = items.partition { definitionOf(it).let { tool -> tool.kind == ToolKind.ADD_ON && tool.baseTool in ids } }
    return AddOnNesting(topLevel, addOns.groupBy { definitionOf(it).baseTool }, definitionOf)
}

/** Prerequisites first, then the other groups in catalog order; add-ons sit under their base tool. */
fun groupTools(tools: List<ToolDefinition>, statuses: Map<String, ToolStatus>): List<ToolGroup> {
    val nesting = nestAddOns(tools) { it }
    fun row(tool: ToolDefinition): ToolRow = ToolRow(tool, statuses[tool.id], nesting.extrasOf(tool).map(::row))
    val (prerequisites, others) = nesting.topLevel.partition { it.kind == ToolKind.PREREQUISITE }
    val groups = others.groupBy { it.group }.map { (title, members) -> ToolGroup(title, members.map(::row)) }
    return listOfNotNull(prerequisites.takeIf { it.isNotEmpty() }?.let { ToolGroup(PREREQUISITES_GROUP, it.map(::row)) }) + groups
}

/** A download plan has no command line, so only a plan with neither is a manual install. */
fun installButtonOf(status: ToolStatus?, plan: InstallPlan?, docsUrl: String, managed: Boolean = false): InstallButton = when {
    plan == null -> InstallButton.Hidden
    managed -> InstallButton.Manage(plan.blocker().isEmpty, plan.blocker().orElse(null))
    isPresent(status) -> InstallButton.Hidden
    plan.command().isEmpty() && plan.download().isEmpty -> InstallButton.Install(true, null, docsUrl.ifBlank { null })
    plan.blocker().isPresent -> InstallButton.Install(false, plan.blocker().get())
    else -> InstallButton.Install(true, null)
}

data class DownloadFact(val label: String, val value: String)

/** What the confirm step lists for a download install instead of a command line. */
fun downloadFactsOf(plan: InstallPlan): List<DownloadFact> {
    val download = plan.download().orElse(null) ?: return emptyList()
    val launcher = if (download.launcher().kind() == LauncherSpec.Kind.JDTLS) "starts the language server with java from PATH" else "runs ${download.launcher().path()}"
    return listOf(
        DownloadFact("Source host", download.host()),
        DownloadFact("Latest version", download.latestUrl()),
        DownloadFact("Archive", "${download.archiveUrlTemplate()}  ({version} = latest version)"),
        DownloadFact("Integrity", "SHA-256 verified; the checksum comes from the same host, so this catches corruption, not a compromised host"),
        DownloadFact("Installs to", download.installDirectory().toString()),
        DownloadFact("Launcher", "${plan.tool().binary()} ($launcher)"),
    )
}

/** The managed launchers sit in `bin` next to the per-tool folders. */
fun binDirectoryOf(download: DownloadPlan): String = download.installDirectory().resolveSibling("bin").toString()

const val PATH_HINT = "Claude sessions started from cc-manager find it. Elsewhere add this folder to PATH:"

fun prerequisiteNote(tool: ToolDefinition, nameOf: (String) -> String?): String? =
    tool.prerequisite.takeIf { it.isNotBlank() }?.let { "Needs ${nameOf(it) ?: it}" }

/** The runner reports the whole output so far; only the tail is kept. */
fun logLinesOf(output: String, limit: Int = LOG_LINE_LIMIT): List<String> {
    val tail = output.takeLast(limit * LOG_CHARS_PER_LINE)
    val lines = tail.lines()
    return (if (tail.length < output.length) lines.drop(1) else lines).takeLast(limit)
}

fun resultMessageOf(result: InstallResult): String = when (result.outcome()) {
    Outcome.INSTALLED -> result.message()
    Outcome.STILL_MISSING, Outcome.BLOCKED -> result.message()
    Outcome.FAILED -> "Installation failed (exit code ${result.exitCode()}): ${result.message()}"
}
