package com.github.krpkbhdn.astrolabe.ui.presets.apply

import com.github.krpkbhdn.astrolabe.preset.PresetApplicationService
import com.github.krpkbhdn.astrolabe.preset.ProjectPreset
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.ui.await
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PresetOfferStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val applications = mock(PresetApplicationService::class.java)
    private val project = Project(7, "demo", Path.of("demo"), Instant.EPOCH)
    private val group = Project(8, "services", Path.of("services"), Instant.EPOCH, com.github.krpkbhdn.astrolabe.project.ModelSelection.DEFAULT, true, null)
    private val presets = listOf(ProjectPreset(1, "Spring", "", "spring", false, false, 1, emptyList()), ProjectPreset(2, "General", "", "general", false, false, 1, emptyList()))
    private var blocked = false
    private var recommended: Set<String> = emptySet()
    private var recommendationFails = false
    private val reviewed = mutableListOf<Pair<Long, List<Long>>>()
    private val state = PresetOfferState(applications, { presets }, { id -> listOf(project, group).firstOrNull { it.id == id } }, scope, { blocked }, { p, ids -> reviewed += p to ids }, {}, { if (recommendationFails) error("detection failed") else recommended })

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `a project without an application is offered with general preselected`() {
        state.offerFor(7)

        await { state.offer != null }
        assertThat(state.offer!!.presetIds).containsExactly(2L)
    }

    @Test
    fun `a failing recommendation still offers general`() {
        recommendationFails = true

        state.offerFor(7)

        await { state.offer != null }
        assertThat(state.offer!!.presetIds).containsExactly(2L)
    }

    @Test
    fun `a project that applied a preset is not offered`() {
        `when`(applications.applications(7)).thenReturn(mapOf(1L to 1))

        state.offerFor(7)

        verify(applications, timeout(2000)).applications(7)
        Thread.sleep(50)
        assertThat(state.offer).isNull()
    }

    @Test
    fun `a declined project is not offered`() {
        `when`(applications.offerDeclined(7)).thenReturn(true)

        state.offerFor(7)

        verify(applications, timeout(2000)).offerDeclined(7)
        Thread.sleep(50)
        assertThat(state.offer).isNull()
    }

    @Test
    fun `groups are never offered`() {
        state.offerFor(8)

        Thread.sleep(50)
        assertThat(state.offer).isNull()
    }

    @Test
    fun `an open dialog skips the offer without remembering it`() {
        blocked = true
        state.offerFor(7)
        Thread.sleep(50)
        assertThat(state.offer).isNull()

        blocked = false
        state.offerFor(7)

        await { state.offer != null }
    }

    @Test
    fun `later asks again next run only, not on the next visit of the same run`() {
        state.offerFor(7)
        await { state.offer != null }
        state.later()

        state.offerFor(7)

        Thread.sleep(50)
        assertThat(state.offer).isNull()
    }

    @Test
    fun `not for this project records the decline`() {
        state.offerFor(7)
        await { state.offer != null }

        state.decline()

        verify(applications, timeout(2000)).declineOffer(7)
        assertThat(state.offer).isNull()
    }

    @Test
    fun `review and apply hands the recommended layers on in list order`() {
        recommended = setOf("general", "spring")
        state.offerFor(7)
        await { state.offer != null }

        state.reviewAndApply()

        assertThat(reviewed).containsExactly(7L to listOf(1L, 2L))
        assertThat(state.offer).isNull()
    }
}
