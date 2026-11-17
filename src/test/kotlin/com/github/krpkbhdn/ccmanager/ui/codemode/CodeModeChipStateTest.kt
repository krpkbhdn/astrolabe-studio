package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.ui.extensions.mcp.McpState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CodeModeChipStateTest {
    private fun project(id: Long, group: Boolean = false) = mock(Project::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.group).thenReturn(group)
    }

    private val created = mutableListOf<ProjectCodeModeState>()
    private fun chip(available: Boolean = true) = CodeModeChipState(available, { created += mock(ProjectCodeModeState::class.java); created.last() }, { mock(McpState::class.java) })

    @Test
    fun `a group project is not tracked`() {
        val chip = chip()
        chip.track(project(1, group = true))
        assertThat(chip.codeMode).isNull()
    }

    @Test
    fun `an unavailable chip tracks nothing`() {
        val chip = chip(available = false)
        chip.track(project(1))
        assertThat(chip.codeMode).isNull()
    }

    @Test
    fun `the same project keeps the loaded state and another one disposes the old`() {
        val chip = chip()
        chip.track(project(1))
        chip.track(project(1))
        assertThat(created).hasSize(1)
        chip.track(project(2))
        assertThat(created).hasSize(2)
        verify(created[0]).dispose()
    }

    @Test
    fun `stop closes the popup and disposes`() {
        val chip = chip()
        chip.track(project(1))
        chip.open = true
        chip.stopIfTracking(1)
        assertThat(chip.open).isFalse()
        assertThat(chip.codeMode).isNull()
        verify(created[0]).dispose()
    }

    @Test
    fun `a leaving screen does not stop the project that replaced it`() {
        val chip = chip()
        chip.track(project(1))
        chip.track(project(2))
        chip.stopIfTracking(1)
        assertThat(chip.codeMode).isNotNull()
    }
}
