package com.github.krpkbhdn.astrolabe.ui.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Outcome
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import com.github.krpkbhdn.astrolabe.ui.tools.Actions
import com.github.krpkbhdn.astrolabe.ui.tools.CodeBlock
import com.github.krpkbhdn.astrolabe.ui.tools.Fact
import com.github.krpkbhdn.astrolabe.ui.tools.Output
import com.github.krpkbhdn.astrolabe.ui.tools.ToolsState
import com.github.krpkbhdn.astrolabe.ui.tools.contentHeight
import com.github.krpkbhdn.astrolabe.ui.tools.dialogWidth

private const val TITLE = "Set up language servers"

/** One confirmation for everything the detected languages need, then the live log and a result per step. */
@Composable
fun LspSetupDialog(setup: LspSetupState, tools: ToolsState) {
    val flow = setup.flow
    if (flow == LspSetupFlow.Idle) return
    ModalHost(setup::close, width = dialogWidth, escapeDismisses = !setup.running) {
        DialogBody(TITLE, Icons.Download, colors.accent) {
            Box(Modifier.fillMaxWidth().height(contentHeight)) {
                when (flow) {
                    is LspSetupFlow.Confirm -> Confirm(flow, setup, tools)
                    is LspSetupFlow.Running -> Output(flow.log, finished = false, message = null) { AppButton("Cancel setup", setup::cancel) }
                    is LspSetupFlow.Done -> Done(flow, setup, tools)
                    LspSetupFlow.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun Confirm(flow: LspSetupFlow.Confirm, setup: LspSetupState, tools: ToolsState) {
    val steps = lspConfirmOf(flow.plan, tools.plans::get, tools::nameOf)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            steps.forEach { step ->
                Label(step.title, style = Type.bodyMedium)
                step.facts.forEach { Fact(it) }
                step.command?.let { CodeBlock(it) }
                step.note?.let { Label(it, color = colors.textSecondary, maxLines = Int.MAX_VALUE) }
            }
        }
        setup.notice?.let { Label(it, color = colors.warning, maxLines = Int.MAX_VALUE) }
        Actions {
            AppButton("Cancel", setup::close)
            AppButton("Set up ${pendingStepCount(flow.plan)}", setup::confirm, variant = ButtonVariant.PRIMARY)
        }
    }
}

@Composable
private fun Done(flow: LspSetupFlow.Done, setup: LspSetupState, tools: ToolsState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            flow.results.forEach { step ->
                Label("${stepTitleOf(step, tools::nameOf)}: ${outcomeLabelOf(step)}", color = tintOf(step), maxLines = Int.MAX_VALUE)
                if (step.message.isNotBlank()) Label(step.message, color = colors.textSecondary, style = Type.small, maxLines = Int.MAX_VALUE)
            }
        }
        Actions { AppButton("Close", setup::close, variant = ButtonVariant.PRIMARY) }
    }
}

@Composable
private fun tintOf(step: LspSetupStep) = when (step.outcome) {
    Outcome.DONE -> colors.success
    Outcome.FAILED -> colors.danger
    else -> colors.warning
}
