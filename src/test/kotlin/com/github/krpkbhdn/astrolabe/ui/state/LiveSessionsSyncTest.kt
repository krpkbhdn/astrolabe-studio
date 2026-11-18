package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.project.ModelSelection
import com.github.krpkbhdn.astrolabe.project.Project
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionsSyncTest {
    private fun project(id: Long, groupId: Long? = null, group: Boolean = false) =
        Project(id, "p$id", Path.of("p$id"), Instant.now(), ModelSelection.DEFAULT, group, groupId)

    private fun session(projectId: Long, status: AgentStatus) =
        SessionSnapshot("s$projectId$status", projectId, "claude", status, null, null, null, null, Instant.now(), Instant.now(), emptyList(), 0, null)

    @Test
    fun projectWithoutSessionsIsOffline() {
        assertThat(projectStatusesOf(emptyList(), listOf(project(1)))).containsEntry(1L, AgentStatus.OFFLINE)
    }

    @Test
    fun projectShowsTheWorstOfItsSessions() {
        val statuses = projectStatusesOf(listOf(session(1, AgentStatus.IDLE), session(1, AgentStatus.NEEDS_ACTION)), listOf(project(1)))

        assertThat(statuses).containsEntry(1L, AgentStatus.NEEDS_ACTION)
    }

    @Test
    fun groupShowsTheWorstOverItsMembers() {
        val projects = listOf(project(1, group = true), project(2, groupId = 1), project(3))
        val statuses = projectStatusesOf(listOf(session(2, AgentStatus.WORKING), session(3, AgentStatus.NEEDS_ACTION)), projects)

        assertThat(statuses).containsEntry(1L, AgentStatus.WORKING).containsEntry(2L, AgentStatus.WORKING).containsEntry(3L, AgentStatus.NEEDS_ACTION)
    }
}
