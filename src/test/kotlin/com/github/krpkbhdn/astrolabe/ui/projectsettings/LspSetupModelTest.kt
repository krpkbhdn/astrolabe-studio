package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Kind
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Outcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LspSetupModelTest {
    private fun step(kind: Kind, outcome: Outcome, id: String = "a") = LspSetupStep(kind, id, outcome, "msg")

    @Test
    fun `the button shows the number of pending steps only`() {
        val plan = listOf(step(Kind.SERVER, Outcome.PENDING), step(Kind.PLUGIN, Outcome.PENDING, "b"), step(Kind.SERVER, Outcome.MANUAL, "c"))

        assertThat(pendingStepCount(plan)).isEqualTo(2)
        assertThat(setupAllLabelOf(plan)).isEqualTo("Set up all (2)")
    }

    @Test
    fun `the button is hidden for an empty plan or one with only manual steps`() {
        assertThat(setupAllLabelOf(emptyList())).isNull()
        assertThat(setupAllLabelOf(listOf(step(Kind.SERVER, Outcome.MANUAL)))).isNull()
    }

    @Test
    fun `confirm entries carry the step text for plugin and manual steps`() {
        val views = lspConfirmOf(listOf(step(Kind.PLUGIN, Outcome.PENDING), step(Kind.SERVER, Outcome.MANUAL, "b")), { null }) { if (it == "a") "Alpha" else null }

        assertThat(views.map { it.title }).containsExactly("Claude plugin: Alpha", "Language server: b")
        assertThat(views.map { it.note }).containsOnly("msg")
    }

    private val names = mapOf("java" to "Java", "ts" to "TypeScript", "node" to "Node") + (1..6).associate { "s$it" to "s$it" }
    private fun summary(vararg steps: LspSetupStep) =
        setupSummaryOf(steps.toList(), { names[it] }) { if (it == "go") null else "$it-lsp@market" }

    @Test
    fun `summary names the servers then the plugins`() {
        val text = summary(step(Kind.PREREQUISITE, Outcome.PENDING, "node"), step(Kind.SERVER, Outcome.PENDING, "java"), step(Kind.PLUGIN, Outcome.PENDING, "java"), step(Kind.PLUGIN, Outcome.PENDING, "ts"))

        assertThat(text).isEqualTo("Node, Java + 2 plugins: java-lsp, ts-lsp")
    }

    @Test
    fun `summary of only a plugin and of duplicates`() {
        assertThat(summary(step(Kind.PLUGIN, Outcome.PENDING, "ts"))).isEqualTo("1 plugin: ts-lsp")
        assertThat(summary(step(Kind.SERVER, Outcome.PENDING, "java"), step(Kind.SERVER, Outcome.PENDING, "java"))).isEqualTo("Java")
    }

    @Test
    fun `summary notes manual steps and is absent for an empty plan`() {
        assertThat(summary(step(Kind.SERVER, Outcome.PENDING, "java"), step(Kind.SERVER, Outcome.MANUAL, "ts"))).isEqualTo("Java; manual: TypeScript")
        assertThat(summary()).isNull()
    }

    @Test
    fun `summary cuts a long list with a more count`() {
        val steps = (1..6).map { step(Kind.SERVER, Outcome.PENDING, "s$it") }.toTypedArray()

        assertThat(summary(*steps)).isEqualTo("s1, s2, s3, s4, +2 more")
    }

    @Test
    fun `summary lists exactly the maximum names without a more count`() {
        val steps = (1..4).map { step(Kind.SERVER, Outcome.PENDING, "s$it") }.toTypedArray()

        assertThat(summary(*steps)).isEqualTo("s1, s2, s3, s4")
    }

    @Test
    fun `summary cuts a long plugin list and strips the marketplace suffix`() {
        val steps = (1..5).map { step(Kind.PLUGIN, Outcome.PENDING, "s$it") }.toTypedArray()

        assertThat(summary(*steps)).isEqualTo("5 plugins: s1-lsp, s2-lsp, s3-lsp, s4-lsp, +1 more")
    }

    @Test
    fun `summary of only manual steps is absent`() {
        assertThat(summary(step(Kind.SERVER, Outcome.MANUAL, "ts"), step(Kind.SERVER, Outcome.MANUAL, "ts"))).isNull()
    }

    @Test
    fun `a pending plugin step without a plugin name is counted and named by the tool`() {
        assertThat(summary(step(Kind.PLUGIN, Outcome.PENDING, "go"))).isEqualTo("1 plugin: go")
        assertThat(summary(step(Kind.PLUGIN, Outcome.PENDING, "java"), step(Kind.PLUGIN, Outcome.PENDING, "go"))).isEqualTo("2 plugins: java-lsp, go")
    }

    @Test
    fun `summary ignores steps that are not pending`() {
        assertThat(summary(step(Kind.SERVER, Outcome.DONE, "java"), step(Kind.PLUGIN, Outcome.SKIPPED, "ts"))).isNull()
    }
}
