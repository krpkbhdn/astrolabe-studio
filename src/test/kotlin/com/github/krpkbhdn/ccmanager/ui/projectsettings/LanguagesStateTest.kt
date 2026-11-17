package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.toolchain.DetectedTechnology
import com.github.krpkbhdn.ccmanager.toolchain.GroupLspService
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolRow
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolsService
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.toolchain.ToolStatus
import com.github.krpkbhdn.ccmanager.ui.await
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LanguagesStateTest {
    private val dir = Path.of("project")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = mock(ProjectToolsService::class.java)

    @AfterEach
    fun stop() = scope.cancel()

    private fun row(id: String, enabled: Boolean = false, userLevel: Boolean = false) = ProjectToolRow(
        ToolDefinition(id, id.uppercase(), "G", ToolKind.LANGUAGE_SERVER, id, listOf(), "$id-lsp@m", "", listOf(), mapOf(), "", mapOf(), mapOf()),
        ToolStatus(id, ToolState.INSTALLED, "", ""), false, enabled, userLevel,
    )

    private fun state() = LanguagesState(service, dir, scope)

    @Test
    fun `a failing enable leaves the row unchanged and sets the error for that tool only`() {
        `when`(service.rows(dir)).thenReturn(listOf(row("a", userLevel = true), row("b")))
        doThrow(IllegalStateException("claude plugin failed")).`when`(service).enableLsp(dir, "a")
        val languages = state()
        languages.refresh()
        await { languages.rows.size == 2 }

        languages.setEnabled("a", true)
        await { languages.errors.containsKey("a") }
        await { !languages.isWorking("a") }

        assertThat(languages.errors["a"]).isEqualTo("claude plugin failed")
        assertThat(languages.errors).doesNotContainKey("b")
        assertThat(languages.rows.first { it.tool.id() == "a" }.pluginEnabled()).isFalse()
    }

    @Test
    fun `a successful enable flips the row and keeps its user level flag`() {
        `when`(service.rows(dir)).thenReturn(listOf(row("a", userLevel = true)))
        val languages = state()
        languages.refresh()
        await { languages.rows.isNotEmpty() }

        languages.setEnabled("a", true)
        await { languages.rows.single().pluginEnabled() }

        assertThat(languages.rows.single().userLevelEnabled()).isTrue()
        assertThat(languages.errors).isEmpty()
    }

    @Test
    fun `a refresh asked for during a refresh runs again afterwards and probes fresh if any request wanted it`() {
        val release = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        doAnswer {
            firstStarted.countDown()
            release.await()
            listOf(row("a"))
        }.`when`(service).rows(dir)
        `when`(service.rowsFresh(dir)).thenReturn(listOf(row("a", enabled = true)))
        val languages = state()

        languages.refresh()
        firstStarted.await()
        languages.refresh()
        languages.refresh(fresh = true)
        languages.refresh()
        release.countDown()

        await { !languages.loading && languages.rows.singleOrNull()?.pluginEnabled() == true }
        verify(service, times(1)).rows(dir)
        verify(service, times(1)).rowsFresh(dir)
    }

    @Test
    fun `the refresh after an install reads the cache and never probes fresh`() {
        `when`(service.rows(dir)).thenReturn(listOf(row("a")))
        val languages = state()

        languages.refreshAfterInstall()

        await { languages.rows.isNotEmpty() }
        verify(service, times(1)).rows(dir)
        verify(service, never()).rowsFresh(dir)
    }

    @Test
    fun `a toggle that finishes while a load is running triggers a reload afterwards`() {
        val release = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val loads = AtomicInteger()
        doAnswer {
            if (loads.incrementAndGet() == 1) {
                firstStarted.countDown()
                release.await()
                listOf(row("a"))
            } else {
                listOf(row("a", enabled = true))
            }
        }.`when`(service).rows(dir)
        val languages = state()
        languages.refresh()
        firstStarted.await()

        languages.setEnabled("a", true)
        await { !languages.isWorking("a") }
        release.countDown()

        await { !languages.loading && languages.rows.singleOrNull()?.pluginEnabled() == true }
        verify(service, times(2)).rows(dir)
    }

    @Test
    fun `toggles of different tools run strictly one at a time while other rows stay interactive`() {
        `when`(service.rows(dir)).thenReturn(listOf(row("a"), row("b"), row("c")))
        val running = AtomicInteger()
        val highest = AtomicInteger()
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        doAnswer {
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                release.await()
            }
            running.decrementAndGet()
            null
        }.`when`(service).enableLsp(any(), anyString())
        val languages = state()
        languages.refresh()
        await { languages.rows.size == 3 }

        languages.setEnabled("a", true)
        firstEntered.await()
        languages.setEnabled("b", true)
        languages.setEnabled("a", true)

        assertThat(languages.isWorking("b")).isTrue()
        assertThat(languages.isWorking("c")).isFalse()
        assertThat(calls.get()).isEqualTo(1)
        release.countDown()
        await { languages.rows.count { it.pluginEnabled() } == 2 }

        assertThat(highest.get()).isEqualTo(1)
        verify(service, times(2)).enableLsp(any(), anyString())
    }

    private fun detected(id: String) = DetectedTechnology(row(id).tool, ToolState.INSTALLED)

    @Test
    fun `an inheriting group loads read-only with the members as the source of each language`() {
        val groupLsp = mock(GroupLspService::class.java)
        val a = Path.of("a")
        val b = Path.of("b")
        `when`(groupLsp.inherits(7)).thenReturn(true)
        `when`(service.rows(dir)).thenReturn(listOf(row("ts")))
        `when`(service.detected(a)).thenReturn(listOf(detected("ts")))
        `when`(service.detected(b)).thenReturn(listOf(detected("ts"), detected("go")))
        val languages = LanguagesState(service, dir, scope, groupLsp, 7) { listOf("A" to a, "B" to b) }

        languages.refresh()
        await { languages.inherits }

        assertThat(languages.sources).containsEntry("ts", listOf("A", "B")).containsEntry("go", listOf("B"))
    }

    @Test
    fun `inheritFromMembers switches the group service and reloads`() {
        val groupLsp = mock(GroupLspService::class.java)
        var inheriting = false
        `when`(groupLsp.inherits(7)).thenAnswer { inheriting }
        doAnswer { inheriting = true; null }.`when`(groupLsp).inherit(7)
        doAnswer { inheriting = false; null }.`when`(groupLsp).override(7)
        `when`(service.rows(dir)).thenReturn(listOf(row("ts")))
        val languages = LanguagesState(service, dir, scope, groupLsp, 7)
        languages.refresh()
        await { languages.rows.isNotEmpty() }

        languages.inheritFromMembers(true)
        await { languages.inherits }
        verify(groupLsp).inherit(7)

        languages.inheritFromMembers(false)
        await { !languages.inherits && !languages.busy }
        verify(groupLsp).override(7)
    }
}
