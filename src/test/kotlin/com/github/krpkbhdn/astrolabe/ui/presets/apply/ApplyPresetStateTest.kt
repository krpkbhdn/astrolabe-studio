package com.github.krpkbhdn.astrolabe.ui.presets.apply

import com.github.krpkbhdn.astrolabe.preset.PresetApplicationService
import com.github.krpkbhdn.astrolabe.preset.ProjectPreset
import com.github.krpkbhdn.astrolabe.preset.apply.PresetApplyService
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.ui.PreviewApply
import com.github.krpkbhdn.astrolabe.ui.await
import com.github.krpkbhdn.astrolabe.ui.previewPresetApplications
import com.github.krpkbhdn.astrolabe.ui.previewPresetApply
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ApplyPresetStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errors = mutableListOf<String>()
    private val project = Project(7, "demo", Path.of("demo"), Instant.EPOCH)
    private val presets = listOf(ProjectPreset(1, "General", "", "general", false, false, 2, emptyList()), ProjectPreset(2, "Spring", "", null, false, false, 1, emptyList()))
    private val service: PresetApplyService = previewPresetApply()
    private val applications: PresetApplicationService = previewPresetApplications()
    private var recommendedKeys: Set<String> = emptySet()
    private val tidyStarted = mutableListOf<Pair<Project, String>>()
    private val state = ApplyPresetState(service, applications, { presets }, { id -> project.takeIf { it.id == id } }, scope, { errors += it }, { recommendedKeys }, { p, prompt, started -> tidyStarted += p to prompt; started() })

    @BeforeEach
    fun reset() {
        PreviewApply.plan = com.github.krpkbhdn.astrolabe.ui.samplePlan()
        PreviewApply.applied = emptyMap()
        PreviewApply.planDelayMillis = 0
    }

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `opening for a project starts at the choice with the first preset marked`() {
        state.open(7)

        assertThat(state.step).isEqualTo(ApplyStep.CHOOSE)
        assertThat(state.chosen).containsExactly(1L)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `opening without a project asks for one and then reviews the given preset`() {
        state.open(null, 2)
        assertThat(state.pickingProject).isTrue()

        state.chooseProject(project)

        assertThat(state.pickingProject).isFalse()
        assertThat(state.step).isEqualTo(ApplyStep.REVIEW)
        await { state.plan != null && state.selection.isNotEmpty() }
        assertThat(state.selection).isEqualTo(defaultSelection(state.plan!!))
    }

    @Test
    fun `chips tell applied from outdated presets`() {
        PreviewApply.applied = mapOf(1L to 1, 2L to 1)

        state.open(7)
        await { state.applied.isNotEmpty() }

        assertThat(state.statusOf(presets[0])).isEqualTo(PresetStatus.OUTDATED)
        assertThat(state.statusOf(presets[1])).isEqualTo(PresetStatus.APPLIED)
    }

    @Test
    fun `plan is null while it is worked out`() {
        PreviewApply.planDelayMillis = 300

        state.open(7, 1)

        assertThat(state.plan).isNull()
        await { state.plan != null }
    }

    @Test
    fun `toggling unchecks a change and never checks a conflict`() {
        state.open(7, 1)
        await { state.plan != null }
        val create = state.plan!!.first { it.componentId() == 2L }
        val conflict = state.plan!!.first { it.componentId() == 9L }

        state.toggle(create)
        state.toggle(conflict)

        assertThat(state.selection).doesNotContain(2L, 9L)
    }

    @Test
    fun `apply sends the checked components and shows the outcomes`() {
        state.open(7, 1)
        await { state.plan != null }
        state.toggle(state.plan!!.first { it.componentId() == 2L })

        state.apply()
        await { state.step == ApplyStep.RESULT }

        verify(service).apply(7, listOf(1L), project.path, defaultSelection(state.plan!!) - 2L)
        assertThat(state.result!!.outcomes()).hasSize(9)
        assertThat(state.running).isFalse()
    }

    @Test
    fun `apply failure stays on the review with the message`() {
        `when`(service.apply(anyLong(), anyList<Long>(), any(Path::class.java), any())).thenThrow(IllegalStateException("disk full"))
        state.open(7, 1)
        await { state.plan != null }

        state.apply()
        await { state.error != null && !state.running }

        assertThat(state.step).isEqualTo(ApplyStep.REVIEW)
        assertThat(state.error).isEqualTo("disk full")
    }

    @Test
    fun `plan failure shows the message and an empty plan`() {
        `when`(service.plan(anyList<Long>(), any(Path::class.java))).thenThrow(IllegalArgumentException("Unknown preset 1"))

        state.open(7, 1)
        await { state.error != null }

        assertThat(state.plan).isEmpty()
        assertThat(state.error).isEqualTo("Unknown preset 1")
    }

    @Test
    fun `back from the review returns to the choice and nothing is applied`() {
        state.open(7, 1)
        await { state.plan != null }

        state.back()

        assertThat(state.step).isEqualTo(ApplyStep.CHOOSE)
        verify(service, never()).apply(anyLong(), anyList<Long>(), any(Path::class.java), any())
    }

    @Test
    fun `close forgets the project`() {
        state.open(7, 1)

        state.close()

        assertThat(state.isOpen).isFalse()
        assertThat(state.project).isNull()
    }

    @Test
    fun `an unknown project is asked for instead of failing`() {
        state.open(99)

        assertThat(state.pickingProject).isTrue()
    }

    @Test
    fun `recommended layers are preselected and flagged`() {
        recommendedKeys = setOf("general")

        state.open(7)
        await { state.recommended.isNotEmpty() }

        assertThat(state.chosen).containsExactly(1L)
        assertThat(state.isRecommended(presets[0])).isTrue()
        assertThat(state.isRecommended(presets[1])).isFalse()
    }

    @Test
    fun `several checked presets are planned together in list order`() {
        state.open(7)
        state.toggleChosen(2)
        state.review()
        await { state.plan != null }

        verify(service).plan(listOf(1L, 2L), project.path)
    }

    @Test
    fun `tidy is offered after an apply only when a claude file exists`() {
        val dir = java.nio.file.Files.createTempDirectory("tidy")
        java.nio.file.Files.writeString(dir.resolve("CLAUDE.md"), "x")
        val withFile = Project(7, "demo", dir, Instant.EPOCH)
        val tidyState = ApplyPresetState(service, applications, { presets }, { withFile }, scope, {}, { emptySet() }, { p, prompt, started -> tidyStarted += p to prompt; started() })
        tidyState.open(7, 1)
        await { tidyState.plan != null }

        tidyState.apply()
        await { tidyState.step == ApplyStep.RESULT && tidyState.tidyOffered }
        tidyState.tidy()

        assertThat(tidyStarted).hasSize(1)
        assertThat(tidyStarted.single().second).contains("Show me the plan first")
        assertThat(tidyState.isOpen).isFalse()
    }
}
