package com.github.krpkbhdn.astrolabe.ui.assistant

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.EmptyState
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.screens.TerminalPane
import com.github.krpkbhdn.astrolabe.ui.state.AppState
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing

/** A Claude session outside any project: its terminal, or a Start button while none is live. */
@Composable
fun AssistantScreen(state: AppState, modifier: Modifier = Modifier) {
    val sessionId = state.assistant.liveSessionId
    if (sessionId == null) {
        EmptyState(
            icon = Icons.Sparkles,
            title = "Assistant",
            message = "Ask questions unrelated to your projects.",
            modifier = modifier,
        ) {
            AppButton("Start", state.assistant::start, variant = ButtonVariant.PRIMARY, icon = Icons.Sparkles)
            AppButton("Help", { state.assistantHelpOpen = true }, icon = Icons.Book)
        }
        return
    }
    TerminalPane(state, sessionId) {
        AppButton("Help", { state.assistantHelpOpen = true }, Modifier.align(Alignment.TopStart).padding(start = Spacing.lg, top = Spacing.xs), icon = Icons.Book, compact = true)
        if (state.proposals.hiddenCount(sessionId) > 0) {
            AppButton(
                "Claude is waiting - Answer", { state.proposals.reopen(sessionId) },
                Modifier.align(Alignment.TopEnd).padding(end = Spacing.lg, top = Spacing.xs),
                variant = ButtonVariant.PRIMARY, icon = Icons.Message, compact = true,
            )
        }
    }
}
