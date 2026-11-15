package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.DetectedTechnology
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DetectedTechModelTest {
    private fun tech(name: String, state: ToolState = ToolState.RESPONDING) = DetectedTechnology(
        ToolDefinition(name.lowercase(), name, "G", ToolKind.LANGUAGE_SERVER, name, listOf(), "", "", "", listOf(), mapOf(), "", mapOf(), mapOf()),
        state,
    )

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
    fun `a missing server is yellow`() {
        assertThat(lspIndicatorOf(listOf(tech("Java"), tech("Go", ToolState.MISSING)))!!.level).isEqualTo(LspLevel.YELLOW)
    }

    @Test
    fun `a failed server is red even when another is missing`() {
        val indicator = lspIndicatorOf(listOf(tech("Go", ToolState.MISSING), tech("Java", ToolState.FAILED)))!!
        assertThat(indicator.level).isEqualTo(LspLevel.RED)
    }

    @Test
    fun `the tooltip has a title and one line per technology including add-ons`() {
        val indicator = lspIndicatorOf(
            listOf(tech("TypeScript"), tech("Angular", ToolState.INSTALLED), tech("Go", ToolState.MISSING), tech("Java", ToolState.FAILED)),
        )!!
        assertThat(indicator.lines).containsExactly(
            "Language servers (LSP)",
            "TypeScript: running",
            "Angular: installed",
            "Go: not installed",
            "Java: not responding",
        )
    }
}
