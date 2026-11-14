package com.github.krpkbhdn.ccmanager.ui.presets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind
import com.github.krpkbhdn.ccmanager.ui.components.AppTextArea
import com.github.krpkbhdn.ccmanager.ui.extensions.FormField
import com.github.krpkbhdn.ccmanager.ui.icons.Icons

private const val SERVER_LINES = 8

/** The one Code Mode setting of a preset: the MCP servers that go through Code Mode, one name per line; the backend checks the names. */
@Composable
fun CodeModeDialog(open: ComponentDialog, state: PresetsState) {
    val editing = open.editing
    val initial = editing?.payload()?.serverNames()?.joinToString("\n").orEmpty()
    val servers = rememberTextFieldState(initial)
    val names = parseServerNames(servers.text.toString())
    ComponentDialogFrame(
        if (editing == null) "Add Code Mode" else "Edit Code Mode", Icons.Code, if (editing == null) "Add Code Mode" else "Save",
        names.isNotEmpty(),
        { onError -> state.save(open, PresetComponentKind.CODE_MODE, CODE_MODE_NAME, PresetPayloads.codeMode(names), onError) },
        state::closeDialog,
        hasInput = { servers.text.toString() != initial },
    ) {
        FormField("MCP servers", hint = "One server name per line. Applying turns Code Mode on for the project with exactly these servers; the servers themselves are not added.") {
            AppTextArea(servers, Modifier.fillMaxWidth(), placeholder = "github\nplaywright", mono = true, minLines = SERVER_LINES, maxLines = SERVER_LINES)
        }
    }
}
