package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer

private const val NOT_APPROVED = "Approve this server in the MCP page first."
private const val NEEDS_SIGN_IN = "Needs a sign-in, which Code Mode cannot do."
private const val PROJECT_NOTE = "Hidden from Claude completely; only Code Mode scripts reach it."
private const val USER_NOTE = "Hidden from the model, but Claude Code still starts it. This can conflict with a server that allows only one connection."

private const val AMBIGUOUS = "Ambiguous name: another server resolves to the same name."
private const val SAVED_BUT_UNUSABLE = "Hidden from Claude at launch, but it cannot run in Code Mode. "

/** One MCP server as the Code Mode page shows it; [selected] is the saved choice, [ineligibleReason] is null when it can run through Code Mode. */
data class CodeModeRow(
    val name: String,
    val scope: ExtensionScope,
    val selected: Boolean,
    val ineligibleReason: String?,
    val note: String?,
    val title: String = name,
) {
    val eligible: Boolean get() = ineligibleReason == null

    /** Unique among rows: a plugin server and a configured one may share a name. */
    val key: String get() = "${scope.name}:$name"

    /** A saved server that cannot run stays checked (launch hides it either way) and can be unchecked, never newly checked. */
    val canChange: Boolean get() = eligible || selected

    val message: String? get() = if (selected && ineligibleReason != null) SAVED_BUT_UNUSABLE + ineligibleReason else ineligibleReason ?: note
}

/**
 * One row per server name (the most specific scope wins, like Claude Code), then one per plugin server (keyed by its canonical name, shown by its server key);
 * [needsSignIn] are the names Claude reported as unauthenticated (plugin servers as `plugin:<plugin>:<server>`).
 * A plugin server whose name another plugin or a configured server shares gets one row, and every row of that name is ineligible, as in Code Mode itself.
 */
fun codeModeRows(servers: List<McpServerConfig>, selected: Collection<String>, needsSignIn: Set<String>, plugins: List<PluginServer> = emptyList()): List<CodeModeRow> {
    val plain = servers.groupBy { it.name() }.map { (_, same) -> same.maxBy { precedence(it.scope()) } }.sortedBy { it.name().lowercase() }
    val pluginNames = plugins.groupBy { it.canonicalName() }
    val ambiguous = pluginNames.filter { (name, same) -> same.size > 1 || plain.any { it.name() == name } }.keys
    return plain.map { rowOf(it, it.name() in selected, it.name() in needsSignIn, it.name() in ambiguous) } +
        pluginNames.values.map { it.first() }.sortedBy { it.canonicalName() }
            .map { pluginRowOf(it, it.canonicalName() in selected, it.listedName() in needsSignIn, it.canonicalName() in ambiguous) }
}

private fun pluginRowOf(server: PluginServer, selected: Boolean, needsSignIn: Boolean, ambiguous: Boolean) = CodeModeRow(
    server.canonicalName(), ExtensionScope.PLUGIN, selected, if (ambiguous) AMBIGUOUS else if (needsSignIn) NEEDS_SIGN_IN else null,
    "Provided by plugin ${server.plugin()}; Claude Code still starts it, hidden from the model.", server.key(),
)

private fun precedence(scope: ExtensionScope) = listOf(ExtensionScope.USER, ExtensionScope.PROJECT, ExtensionScope.LOCAL).indexOf(scope)

private fun rowOf(server: McpServerConfig, selected: Boolean, needsSignIn: Boolean, ambiguous: Boolean): CodeModeRow {
    val reason = when {
        ambiguous -> AMBIGUOUS
        server.scope() == ExtensionScope.PROJECT && server.approval() != McpApproval.APPROVED -> NOT_APPROVED
        needsSignIn -> NEEDS_SIGN_IN
        else -> null
    }
    return CodeModeRow(server.name(), server.scope(), selected, reason, if (server.scope() == ExtensionScope.PROJECT) PROJECT_NOTE else USER_NOTE)
}

/** The server list after switching [name]; order stays as saved, new names go last. */
fun withServer(selected: List<String>, name: String, on: Boolean): List<String> =
    if (on) (selected - name) + name else selected - name

/** Saved names that no listed server explains any more; kept so a temporary read failure never drops the choice. */
fun unlistedServers(selected: List<String>, rows: List<CodeModeRow>): List<String> = selected - rows.map { it.name }.toSet()
