package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.colors

/** Asks what to do with an unsaved draft before the action that would replace it runs; the action runs after Save or Discard. */
@Composable
fun WebUnsavedDraftDialog(view: WebView, state: WebState) {
    val action = view.unsavedAction ?: return
    val saveable = view.canSave && !view.draftSaving
    val answer = { choice: UnsavedChoice ->
        if (DraftGuard.runnable(choice, saveable)) {
            view.unsavedAction = null
            when (choice) {
                UnsavedChoice.SAVE -> { view.afterSave = action; state.saveDraft(view) }
                UnsavedChoice.DISCARD -> { WebContextActions.dismiss(view); state.cancelDraft(view); action() }
                UnsavedChoice.KEEP -> {
                    if (view.contextMenu?.feedback != true) WebContextActions.dismiss(view)
                    view.keepEditingTick++
                }
            }
        }
    }
    Box(
        Modifier.onPreviewKeyEvent { event ->
            val choice = if (event.type == KeyEventType.KeyDown) DraftGuard.choiceOf(event.key, event.utf16CodePoint, event.isCtrlPressed, event.isAltPressed || event.isMetaPressed) else null
            choice?.also(answer) != null
        },
    ) {
        ModalHost({ answer(UnsavedChoice.KEEP) }) {
            DialogBody("Unsaved changes", Icons.Alert, colors.warning) {
                Label("This feedback has changes that are not saved yet.", color = colors.textSecondary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                    UnsavedChoice.entries.reversed().forEach { choice ->
                        AppButton(
                            "${choice.label} [${choice.hint}]", { answer(choice) },
                            variant = when (choice) {
                                UnsavedChoice.SAVE -> ButtonVariant.PRIMARY
                                UnsavedChoice.DISCARD -> ButtonVariant.DANGER
                                UnsavedChoice.KEEP -> ButtonVariant.SECONDARY
                            },
                            enabled = DraftGuard.runnable(choice, saveable),
                        )
                    }
                }
            }
        }
    }
}
