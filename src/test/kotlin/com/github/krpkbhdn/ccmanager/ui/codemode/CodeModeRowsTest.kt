package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeModeRowsTest {
    private fun server(name: String, scope: ExtensionScope, approval: McpApproval = McpApproval.NOT_REQUIRED) =
        McpServerConfig(name, scope, "http", "https://x", approval)

    @Test
    fun anApprovedProjectServerIsEligible() {
        val row = codeModeRows(listOf(server("a", ExtensionScope.PROJECT, McpApproval.APPROVED)), listOf("a"), emptySet()).single()

        assertThat(row.eligible).isTrue()
        assertThat(row.selected).isTrue()
        assertThat(row.note).contains("completely")
    }

    @Test
    fun aSavedProjectServerThatIsNotApprovedStaysCheckedWithAWarningAndCanBeUnchecked() {
        val row = codeModeRows(listOf(server("a", ExtensionScope.PROJECT, McpApproval.PENDING)), listOf("a"), emptySet()).single()

        assertThat(row.ineligibleReason).contains("Approve")
        assertThat(row.selected).isTrue()
        assertThat(row.canChange).isTrue()
        assertThat(row.message).contains("Hidden from Claude").contains("cannot run in Code Mode").contains("Approve")
    }

    @Test
    fun anUnsavedIneligibleServerIsUncheckedAndCannotBeChecked() {
        val row = codeModeRows(listOf(server("a", ExtensionScope.PROJECT, McpApproval.PENDING)), emptyList(), emptySet()).single()

        assertThat(row.selected).isFalse()
        assertThat(row.canChange).isFalse()
        assertThat(row.message).isEqualTo(row.ineligibleReason)
    }

    @Test
    fun aSavedServerNeedingSignInStaysCheckedWithTheReason() {
        val row = codeModeRows(listOf(server("a", ExtensionScope.USER)), listOf("a"), setOf("a")).single()

        assertThat(row.selected).isTrue()
        assertThat(row.message).contains("cannot run in Code Mode").contains("sign-in")
    }

    @Test
    fun aServerNeedingSignInIsIneligible() {
        val row = codeModeRows(listOf(server("a", ExtensionScope.USER)), emptyList(), setOf("a")).single()

        assertThat(row.ineligibleReason).contains("sign-in")
        assertThat(row.note).contains("still starts")
    }

    @Test
    fun theMostSpecificScopeWinsForADuplicateName() {
        val rows = codeModeRows(listOf(server("a", ExtensionScope.USER), server("a", ExtensionScope.LOCAL)), emptyList(), emptySet())

        assertThat(rows.single().scope).isEqualTo(ExtensionScope.LOCAL)
    }

    @Test
    fun switchingAServerAddsOrRemovesItOnce() {
        assertThat(withServer(listOf("a"), "b", true)).containsExactly("a", "b")
        assertThat(withServer(listOf("a", "b"), "b", true)).containsExactly("a", "b")
        assertThat(withServer(listOf("a", "b"), "a", false)).containsExactly("b")
    }

    private fun plugin(name: String, key: String) = PluginServer(
        name, key,
        McpServerDefinition(PluginMcpServers.canonicalName(name, key), ExtensionScope.PLUGIN, McpServerDefinition.Transport.STDIO, "npx", emptyList(), emptyMap(), "", emptyMap(), null),
    )

    @Test
    fun aPluginServerRowIsStoredByCanonicalNameShownByKeyAndExplainsWhereItComesFrom() {
        val row = codeModeRows(emptyList(), listOf("plugin_playwright_playwright"), emptySet(), listOf(plugin("playwright", "playwright"))).single()

        assertThat(row.name).isEqualTo("plugin_playwright_playwright")
        assertThat(row.title).isEqualTo("playwright")
        assertThat(row.scope).isEqualTo(ExtensionScope.PLUGIN)
        assertThat(row.eligible).isTrue()
        assertThat(row.selected).isTrue()
        assertThat(row.message).contains("Provided by plugin playwright").contains("hidden from the model")
    }

    @Test
    fun aPluginServerNeedingSignInIsIneligibleByItsListedName() {
        val row = codeModeRows(emptyList(), emptyList(), setOf("plugin:p:s"), listOf(plugin("p", "s"))).single()

        assertThat(row.ineligibleReason).contains("sign-in")
        assertThat(row.canChange).isFalse()
    }

    @Test
    fun aServerAndAPluginServerWithTheSameKeyAreTwoRowsWithDistinctNames() {
        val rows = codeModeRows(listOf(server("db", ExtensionScope.USER)), emptyList(), emptySet(), listOf(plugin("p", "db")))

        assertThat(rows.map { it.name }).containsExactly("db", "plugin_p_db")
    }

    @Test
    fun aPluginServerAndAConfiguredServerWithTheSameNameGetDistinctKeysAndBothBecomeAmbiguous() {
        val rows = codeModeRows(listOf(server("plugin_p_db", ExtensionScope.PROJECT, McpApproval.APPROVED)), listOf("plugin_p_db"), emptySet(), listOf(plugin("p", "db")))

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.key }).doesNotHaveDuplicates()
        assertThat(rows.map { it.ineligibleReason }).allMatch { it!!.contains("Ambiguous") }
    }

    @Test
    fun twoPluginServersWithTheSameCanonicalNameBecomeOneAmbiguousRow() {
        val rows = codeModeRows(emptyList(), emptyList(), emptySet(), listOf(plugin("a.b", "s"), plugin("a_b", "s")))

        assertThat(rows).hasSize(1)
        assertThat(rows.single().ineligibleReason).contains("Ambiguous")
    }
}
