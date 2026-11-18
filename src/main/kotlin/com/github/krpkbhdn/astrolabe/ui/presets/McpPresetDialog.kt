package com.github.krpkbhdn.astrolabe.ui.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind
import com.github.krpkbhdn.astrolabe.ui.components.AppTextField
import com.github.krpkbhdn.astrolabe.ui.components.Glyph
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.extensions.FormField
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.ServerConfigFields
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.ServerFormState
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.nameProblem
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private const val SECRETS_WARNING = "The config is stored in plain text in the app database. Do not paste secrets such as tokens or passwords."
private const val PAIR_HINT = "Stored in the preset as plain text."

/** An MCP server registered for the project: the same form as the MCP screen's Add server, without a scope. */
@Composable
fun McpPresetDialog(open: ComponentDialog, state: PresetsState) {
    val editing = open.editing
    val initialName = editing?.name().orEmpty()
    val name = rememberTextFieldState(initialName)
    val form = remember { ServerFormState(editing?.payload()?.path("config")?.let(::prettyJson)) }
    val others = state.find(open.presetId)?.components().orEmpty().filter { it.kind() == PresetComponentKind.MCP_SERVER && it.id() != editing?.id() }.map { it.name() }
    val nameText = name.text.toString().trim()
    val nameError = if (nameText.isEmpty()) null else nameProblem(nameText, others)
    ComponentDialogFrame(
        if (editing == null) "Add MCP server" else "Edit MCP server", Icons.Server, if (editing == null) "Add server" else "Save",
        nameText.isNotEmpty() && nameError == null && form.problem == null,
        { onError -> state.save(open, PresetComponentKind.MCP_SERVER, nameText, PresetPayloads.mcp(form.json), onError) },
        state::closeDialog,
        hasInput = { name.text.toString() != initialName || form.hasInput },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
            Glyph(Icons.Alert, colors.warning)
            Label(SECRETS_WARNING, color = colors.textSecondary, style = Type.small, maxLines = 3)
        }
        ServerConfigFields(form, pairHint = PAIR_HINT) {
            FormField("Name", error = nameError, hint = "How Claude refers to the server, for example github") {
                AppTextField(name, Modifier.fillMaxWidth(), placeholder = "server-name")
            }
        }
    }
}
