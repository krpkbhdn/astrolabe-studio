package com.github.krpkbhdn.astrolabe.ui.proposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import com.github.krpkbhdn.astrolabe.claude.proposal.PermissionProposal
import com.github.krpkbhdn.astrolabe.claude.proposal.Proposal
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalAnswer
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.Chip
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.GhostButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.components.isConfirmKey
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

/** What identifies a proposal in the dialog's header: [tags] (tool, agent) as small chips, [position] "1 of 3" when several wait. */
class FrameTitle(val icon: ImageVector, val kind: String, val tint: Color, val tags: List<String>, val position: String?)

/** Who asked, from the proposal's origin: only app-held proposals say they come from the Assistant or a Code Mode script. */
internal fun requesterOf(proposal: Proposal): String = when ((proposal as? PermissionProposal)?.origin()) {
    PermissionProposal.Origin.ASSISTANT -> "the Assistant through astrolabe-app"
    PermissionProposal.Origin.CODE_MODE -> "a Code Mode script"
    else -> "astrolabe"
}

/** The proposal a dialog is about, with the state that answers, hides or dismisses it and keeps what the user typed. */
internal class ProposalContext(private val state: ProposalState, val proposal: Proposal) {
    fun answer(answer: ProposalAnswer) = state.answer(proposal, answer)

    fun dismiss() = state.dismiss(proposal)

    /** A proposal held in the app (Assistant tool call, Code Mode tool call) has no terminal dialog: dismissing it denies it. */
    val viaApp = state.heldForApp(proposal)

    val requester = requesterOf(proposal)

    fun hide() = state.hide(proposal)

    fun <T : Any> draft(create: () -> T): T = state.draft(proposal, create)
}

/** Compact button of a dialog footer. */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, variant: ButtonVariant = ButtonVariant.SECONDARY, enabled: Boolean = true) =
    AppButton(text, onClick, modifier, variant, enabled = enabled, compact = true)

/**
 * Shared dialog of every proposal: Esc or a click outside only hides it (the proposal stays pending), "Answer in terminal" hands
 * it back to claude (for an astrolabe-app request, the header button denies it). [onCtrlEnter] is the dialog's default action, tried before any field sees the key. The footer is one row:
 * [leading] controls, the key [hints], then [actions]; [aboveFooter] stays pinned right over it.
 */
@Composable
internal fun ProposalDialogFrame(
    title: FrameTitle,
    context: ProposalContext,
    width: Dp,
    initialFocus: FocusRequester,
    hints: String,
    actions: @Composable RowScope.() -> Unit,
    leading: @Composable RowScope.() -> Unit = {},
    aboveFooter: @Composable () -> Unit = {},
    onCtrlEnter: () -> Unit = {},
    body: @Composable () -> Unit,
) {
    val ctrlEnter = Modifier.onPreviewKeyEvent { event ->
        (event.type == KeyEventType.KeyDown && isConfirmKey(event.key) && event.isCtrlPressed).also { if (it) onCtrlEnter() }
    }
    ModalHost(context::hide, ctrlEnter, width = width, takeFocus = false) {
        DialogBody(
            title.kind, title.icon, title.tint,
            actions = { Footer(hints, leading, actions, aboveFooter) },
            headerTrailing = {
                title.tags.forEach { Chip(it, colors.textSecondary) }
                title.position?.let { Chip(it, colors.accent) }
                Box(Modifier.weight(1f))
                GhostButton(if (context.viaApp) "Deny" else "Answer in terminal", context::dismiss)
            },
            content = {
                LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
                body()
            },
        )
    }
}

@Composable
private fun Footer(hints: String, leading: @Composable RowScope.() -> Unit, actions: @Composable RowScope.() -> Unit, aboveFooter: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        aboveFooter()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            leading()
            Label(hints, Modifier.weight(1f), color = colors.textTertiary, style = Type.small, tooltipWhenTruncated = true)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
