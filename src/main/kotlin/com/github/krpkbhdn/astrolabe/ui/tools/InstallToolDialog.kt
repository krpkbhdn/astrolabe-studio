package com.github.krpkbhdn.astrolabe.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.DownloadPlan
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.Outcome
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.components.TextLink
import com.github.krpkbhdn.astrolabe.ui.components.ThinScrollbar
import com.github.krpkbhdn.astrolabe.ui.components.copyToClipboard
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

internal val dialogWidth = 560.dp
internal val contentHeight = 360.dp
private const val PERMISSION_NOTE = "This runs on your computer with your permissions. The app does not request administrator rights; some installers may ask for approval themselves."

/** Confirm, live log and result of one tool install, or the manual install hint; the size is fixed and closing is refused while it runs. */
@Composable
fun InstallToolDialog(tools: ToolsState) {
    val flow = tools.install
    val plan = when (flow) {
        InstallFlow.Idle -> return
        is InstallFlow.Confirm -> flow.plan
        is InstallFlow.Manual -> flow.plan
        is InstallFlow.Running -> flow.plan
        is InstallFlow.Done -> flow.plan
        is InstallFlow.ConfirmRemove -> flow.plan
        is InstallFlow.Removing -> flow.plan
        is InstallFlow.Removed -> flow.plan
    }
    val verb = when (flow) {
        is InstallFlow.ConfirmRemove, is InstallFlow.Removing, is InstallFlow.Removed -> "Remove"
        else -> if (tools.isManaged(plan.tool().id())) "Update" else "Install"
    }
    ModalHost(tools::closeInstall, width = dialogWidth, escapeDismisses = !flow.isBusy) {
        DialogBody("$verb ${plan.tool().name()}", Icons.Download, colors.accent) {
            Box(Modifier.fillMaxWidth().height(contentHeight)) {
                when (flow) {
                    is InstallFlow.Confirm -> Confirm(plan, verb, tools)
                    is InstallFlow.Manual -> Manual(plan, tools)
                    is InstallFlow.Running -> Output(flow.log, finished = false, message = null) { AppButton("Cancel install", tools::cancelInstall) }
                    is InstallFlow.Done -> Done(flow, tools)
                    is InstallFlow.ConfirmRemove -> ConfirmRemove(plan, tools)
                    is InstallFlow.Removing -> Label("Removing...", color = colors.textSecondary)
                    is InstallFlow.Removed -> Removed(flow, tools)
                    InstallFlow.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun Confirm(plan: InstallPlan, verb: String, tools: ToolsState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (plan.download().isPresent) {
                downloadFactsOf(plan, tools.selectedChannel(plan.tool().id())).forEach { Fact(it) }
            } else {
                CodeBlock(plan.commandLine())
                Label(PERMISSION_NOTE, color = colors.textSecondary, maxLines = Int.MAX_VALUE)
            }
        }
        Actions {
            AppButton("Cancel", tools::closeInstall)
            AppButton(verb, tools::confirmInstall, variant = ButtonVariant.PRIMARY)
        }
    }
}

@Composable
internal fun Fact(fact: DownloadFact) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Label(fact.label, color = colors.textTertiary, style = Type.overline)
        SelectionContainer { Label(fact.value, style = Type.monoSmall, maxLines = Int.MAX_VALUE) }
    }
}

@Composable
private fun ConfirmRemove(plan: InstallPlan, tools: ToolsState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Label("Remove ${plan.tool().name()} and its launcher that astrolabe installed? Running sessions that use it may need a restart.", Modifier.weight(1f), maxLines = Int.MAX_VALUE)
        Actions {
            AppButton("Cancel", tools::closeInstall)
            AppButton("Uninstall", tools::confirmUninstall, variant = ButtonVariant.PRIMARY)
        }
    }
}

@Composable
private fun Removed(flow: InstallFlow.Removed, tools: ToolsState) {
    val tint = if (flow.result.outcome() == Outcome.INSTALLED) colors.success else colors.danger
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Label(flow.result.message(), Modifier.weight(1f), color = tint, maxLines = Int.MAX_VALUE)
        Actions { AppButton("Close", tools::closeInstall, variant = ButtonVariant.PRIMARY) }
    }
}

@Composable
private fun Manual(plan: InstallPlan, tools: ToolsState) {
    val view = manualInstallOf(plan.manualCommand(), plan.tool().docsUrl())
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Label(view.message, maxLines = Int.MAX_VALUE)
            view.command?.let { command ->
                SelectionContainer { CodeBlock(command) }
                Row { AppButton("Copy", { copyToClipboard(command) }, compact = true) }
            }
            view.docsUrl?.let { url -> Row { TextLink("Open download page") { tools.openDocs(url) } } }
        }
        Actions { AppButton("Close", tools::closeInstall, variant = ButtonVariant.PRIMARY) }
    }
}

@Composable
private fun Done(flow: InstallFlow.Done, tools: ToolsState) {
    val tint = if (flow.result.outcome() == Outcome.INSTALLED) colors.success else if (flow.result.outcome() == Outcome.STILL_MISSING) colors.warning else colors.danger
    val download = flow.plan.download().orElse(null)?.takeIf { flow.result.outcome() == Outcome.INSTALLED }
    Output(flow.log, finished = true, message = resultMessageOf(flow.result) to tint, extra = download?.let { shown -> @Composable { PathHint(shown, flow.result.status().version()) } }) {
        AppButton("Close", tools::closeInstall, variant = ButtonVariant.PRIMARY)
    }
}

@Composable
private fun PathHint(download: DownloadPlan, version: String) {
    val bin = binDirectoryOf(download)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (version.isNotBlank()) Label("Installed version: $version")
        Label(PATH_HINT, color = colors.textSecondary, maxLines = Int.MAX_VALUE)
        SelectionContainer { CodeBlock(bin) }
        Row { AppButton("Copy", { copyToClipboard(bin) }, compact = true) }
    }
}

@Composable
internal fun Output(
    log: List<String>,
    finished: Boolean,
    message: Pair<String, androidx.compose.ui.graphics.Color>?,
    extra: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        message?.let { (text, tint) -> Label(text, color = tint, maxLines = 3) }
        extra?.invoke()
        LogViewport(log.joinToString("\n").ifBlank { if (finished) "No output." else "Starting..." }, log.size, Modifier.weight(1f))
        Actions(actions)
    }
}

/** A scrolling log that follows the newest line until the user scrolls up, and again once they scroll back to the end. */
@Composable
private fun LogViewport(text: String, lineCount: Int, modifier: Modifier) {
    val scroll = rememberScrollState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { max -> if (following) scroll.scrollTo(max) }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving -> if (!moving) following = keepsFollowing(scroll.value, scroll.maxValue) }
    }
    LaunchedEffect(lineCount) { if (following) scroll.scrollTo(scroll.maxValue) }
    Box(modifier.fillMaxWidth().codeBox()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(Spacing.md)) {
            Label(text, style = Type.monoSmall, maxLines = Int.MAX_VALUE)
        }
        ThinScrollbar(scroll)
    }
}

@Composable
internal fun CodeBlock(text: String) {
    Column(Modifier.fillMaxWidth().codeBox().padding(Spacing.md)) {
        Label(text, style = Type.monoBody, maxLines = Int.MAX_VALUE)
    }
}

private fun Modifier.codeBox(): Modifier = composed {
    val shape = RoundedCornerShape(Radius.md)
    this.background(colors.terminal, shape).border(1.dp, colors.border, shape)
}

@Composable
internal fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) { content() }
}
