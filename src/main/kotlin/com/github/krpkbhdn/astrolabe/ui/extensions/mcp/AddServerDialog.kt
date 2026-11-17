package com.github.krpkbhdn.astrolabe.ui.extensions.mcp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope
import com.github.krpkbhdn.astrolabe.ui.components.AppTextField
import com.github.krpkbhdn.astrolabe.ui.components.DialogActions
import com.github.krpkbhdn.astrolabe.ui.components.DialogBody
import com.github.krpkbhdn.astrolabe.ui.components.ModalHost
import com.github.krpkbhdn.astrolabe.ui.components.Segment
import com.github.krpkbhdn.astrolabe.ui.components.SegmentedControl
import com.github.krpkbhdn.astrolabe.ui.extensions.ExtensionContext
import com.github.krpkbhdn.astrolabe.ui.extensions.FormField
import com.github.krpkbhdn.astrolabe.ui.extensions.ScopeBadge
import com.github.krpkbhdn.astrolabe.ui.extensions.description
import com.github.krpkbhdn.astrolabe.ui.extensions.label
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private val dialogWidth = 560.dp

/** Collects a server as form fields or as pasted JSON and hands the JSON object to [McpState.add]. */
@Composable
fun AddServerDialog(context: ExtensionContext, state: McpState) {
    var scope by remember { mutableStateOf(context.writableScopes.first()) }
    val form = remember { ServerFormState() }
    val name = rememberTextFieldState()
    val taken = state.servers.filter { it.scope == scope }.map { it.name }
    val nameText = name.text.toString().trim()
    val nameError = if (nameText.isEmpty()) null else nameProblem(nameText, taken)
    val canAdd = nameText.isNotEmpty() && nameError == null && form.problem == null && state.job == null
    val dismiss = { state.adding = false }
    ModalHost(dismiss, width = dialogWidth, hasInput = { name.text.isNotEmpty() || form.hasInput }) {
        DialogBody("Add MCP server", Icons.Server, colors.accent, actions = { DialogActions("Add server", canAdd, false, { state.add(nameText, form.json, scope) }, dismiss) }) {
            ServerConfigFields(form) {
                FormField("Name", error = nameError, hint = "How Claude refers to the server, for example github") {
                    AppTextField(name, Modifier.fillMaxWidth(), placeholder = "server-name")
                }
                ScopeChoice(context, scope) { scope = it }
            }
        }
    }
}

@Composable
private fun ScopeChoice(context: ExtensionContext, scope: ExtensionScope, onSelect: (ExtensionScope) -> Unit) {
    FormField("Available to", hint = scope.description()) {
        if (context.writableScopes.size > 1) {
            SegmentedControl(context.writableScopes.map { Segment(it, it.label()) }, scope, onSelect)
        } else {
            ScopeBadge(scope)
        }
    }
}
