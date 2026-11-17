package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.toolchain.DetectedTechnology
import com.github.krpkbhdn.astrolabe.toolchain.LspPluginState
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.ToolKind
import com.github.krpkbhdn.astrolabe.toolchain.ToolState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DetectedTechModelTest {
    private fun tech(name: String, state: ToolState? = ToolState.RESPONDING, plugin: LspPluginState = LspPluginState.ENABLED) = DetectedTechnology(
        ToolDefinition(name.lowercase(), name, "G", ToolKind.LANGUAGE_SERVER, name, listOf(), "", "", listOf(), mapOf(), "", mapOf(), mapOf()),
        state,
        plugin,
    )

    private fun off(name: String, plugin: LspPluginState = LspPluginState.DISABLED) = tech(name, null, plugin)

    @Test
    fun `an empty list gives no indicator`() {
        assertThat(lspIndicatorOf(listOf())).isNull()
    }

    @Test
    fun `every server installed or responding is green`() {
        val indicator = lspIndicatorOf(listOf(tech("Java"), tech("Go", ToolState.INSTALLED)))!!
        assertThat(indicator.level).isEqualTo(LspLevel.GREEN)
    }

    @Test
    fun `a failed server line shows its reason, or not responding without one`() {
        val reason = "Gradle output redirect did not run (VERIFIED)"
        val java = tech("Java", ToolState.FAILED).withFailure(reason)
        assertThat(lspIndicatorOf(listOf(java))!!.lines).contains("Java: $reason")
        assertThat(lspIndicatorOf(listOf(tech("Go", ToolState.FAILED)))!!.lines).contains("Go: not responding")
    }

    @Test
    fun `a missing server is yellow`() {
        assertThat(lspIndicatorOf(listOf(tech("Java"), tech("Go", ToolState.MISSING)))!!.level).isEqualTo(LspLevel.YELLOW)
    }

    @Test
    fun `a failed server is red even when another is missing`() {
        val indicator = lspIndicatorOf(listOf(tech("Go", ToolState.MISSING), tech("Java", ToolState.FAILED)))!!
        assertThat(indicator.level).isEqualTo(LspLevel.RED)
    }

    @Test
    fun `the tooltip has a title and one line per technology`() {
        val indicator = lspIndicatorOf(
            listOf(tech("TypeScript"), tech("Python", ToolState.INSTALLED), tech("Go", ToolState.MISSING), tech("Java", ToolState.FAILED)),
        )!!
        assertThat(indicator.lines).containsExactly(
            "Language servers (LSP)",
            "TypeScript: running",
            "Python: installed",
            "Go: not installed",
            "Java: not responding",
        )
    }

    @Test
    fun `every plugin off is gray with the explaining tooltip and no warning`() {
        val indicator = lspIndicatorOf(listOf(off("Go"), off("Java", LspPluginState.NOT_INSTALLED)))!!
        assertThat(indicator.level).isEqualTo(LspLevel.GRAY)
        assertThat(indicator.lines).containsExactly("Language servers (LSP)", "LSP plugins are disabled for this project")
        assertThat(indicator.warningCount).isZero()
    }

    @Test
    fun `a partly enabled set with healthy servers is green with a warning for the rest`() {
        val indicator = lspIndicatorOf(listOf(tech("Java"), off("Go"), off("Rust", LspPluginState.NOT_INSTALLED)))!!
        assertThat(indicator.level).isEqualTo(LspLevel.GREEN)
        assertThat(indicator.warningCount).isEqualTo(2)
        assertThat(indicator.lines).containsExactly(
            "Language servers (LSP)",
            "Java: running",
            "Not enabled: Go (plugin disabled), Rust (plugin not installed)",
        )
    }

    @Test
    fun `a partly enabled set with a broken server is red and still warns`() {
        val indicator = lspIndicatorOf(listOf(tech("Java", ToolState.FAILED), off("Go")))!!
        assertThat(indicator.level).isEqualTo(LspLevel.RED)
        assertThat(indicator.warningCount).isEqualTo(1)
    }

    @Test
    fun `every plugin on has no warning`() {
        assertThat(lspIndicatorOf(listOf(tech("Java"), tech("Go", ToolState.INSTALLED)))!!.warningCount).isZero()
    }
}
