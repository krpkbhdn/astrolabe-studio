package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope
import com.github.krpkbhdn.ccmanager.ui.settings.RuntimeStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeModeIndicatorModelTest {
    private fun row(name: String, selected: Boolean = true, reason: String? = null) = CodeModeRow(name, ExtensionScope.USER, selected, reason, null)

    private fun indicator(enabled: Boolean = true, rows: List<CodeModeRow> = listOf(row("jira")), missing: List<String> = emptyList(), backend: IsolationBackend = IsolationBackend.GRAALJS, status: RuntimeStatus? = null) =
        codeModeIndicatorOf(enabled, rows, missing, backend, status)

    @Test
    fun `disabled is off whatever the rest says`() {
        assertThat(indicator(enabled = false, backend = IsolationBackend.DOCKER, status = RuntimeStatus(false, "x", null)).level).isEqualTo(CodeModeLevel.OFF)
    }

    @Test
    fun `enabled with a usable server and sandbox is green`() {
        assertThat(indicator().level).isEqualTo(CodeModeLevel.GREEN)
    }

    @Test
    fun `no selected server is yellow`() {
        assertThat(indicator(rows = listOf(row("jira", selected = false))).level).isEqualTo(CodeModeLevel.YELLOW)
    }

    @Test
    fun `a selected ineligible server is yellow and named`() {
        val result = indicator(rows = listOf(row("jira", reason = "Needs a sign-in")))
        assertThat(result.level).isEqualTo(CodeModeLevel.YELLOW)
        assertThat(result.lines).anyMatch { it.startsWith("jira: Needs a sign-in") }
    }

    @Test
    fun `a saved server that is gone is yellow`() {
        assertThat(indicator(missing = listOf("old")).level).isEqualTo(CodeModeLevel.YELLOW)
    }

    @Test
    fun `an unselected ineligible server does not matter`() {
        assertThat(indicator(rows = listOf(row("jira"), row("other", selected = false, reason = "x"))).level).isEqualTo(CodeModeLevel.GREEN)
    }

    @Test
    fun `a container runtime that is unavailable is red`() {
        assertThat(indicator(backend = IsolationBackend.DOCKER, status = RuntimeStatus(false, "daemon not running", null)).level).isEqualTo(CodeModeLevel.RED)
    }

    @Test
    fun `a missing image is red and said so`() {
        val result = indicator(backend = IsolationBackend.PODMAN, status = RuntimeStatus(true, "", false))
        assertThat(result.level).isEqualTo(CodeModeLevel.RED)
        assertThat(result.lines).anyMatch { it.contains("image is not present") }
    }

    @Test
    fun `a container runtime still being detected is not red`() {
        assertThat(indicator(backend = IsolationBackend.DOCKER, status = null).level).isEqualTo(CodeModeLevel.GREEN)
    }
}
