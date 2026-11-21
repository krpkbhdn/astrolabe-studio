package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.toolchain.DetectedTechnology
import com.github.krpkbhdn.astrolabe.toolchain.LspHealthMonitor
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.ToolKind
import com.github.krpkbhdn.astrolabe.toolchain.ToolState
import com.github.krpkbhdn.astrolabe.ui.await
import java.nio.file.Path
import java.time.Instant
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class DetectedTechStateTest {
    private val dir = Path.of("project")
    private val otherDir = Path.of("other")
    private val project = Project(1, "p", dir, Instant.EPOCH, null)
    private val other = Project(3, "o", otherDir, Instant.EPOCH, null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val health = mock(LspHealthMonitor::class.java)
    private val consumers = mutableMapOf<Path, Consumer<List<DetectedTechnology>>>()
    private val unsubscribed = mutableListOf<Path>()

    @AfterEach
    fun stop() = scope.cancel()

    private fun tech(id: String) = DetectedTechnology(
        ToolDefinition(id, id, "G", ToolKind.LANGUAGE_SERVER, id, listOf(), "", "", listOf(), mapOf(), "", mapOf(), mapOf()), ToolState.INSTALLED,
    )

    private fun capturingWatch() {
        doAnswer {
            val path = it.getArgument<Path>(0)
            consumers[path] = it.getArgument(1)
            Runnable { unsubscribed.add(path) }
        }.`when`(health).watch(any(), any())
    }

    @Test
    fun `tracking subscribes and a monitor push fills the map`() {
        capturingWatch()
        val state = DetectedTechState(health, scope)
        state.track(project)
        consumers.getValue(dir).accept(listOf(tech("a")))
        assertThat(state.of(1).map { it.tool().id() }).containsExactly("a")
    }

    @Test
    fun `tracking probes at once off the calling thread`() {
        capturingWatch()
        val caller = Thread.currentThread()
        val probeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        doAnswer { probeThread.set(Thread.currentThread()); null }.`when`(health).checkNow(dir)
        DetectedTechState(health, scope).track(project)
        await { probeThread.get() != null }
        assertThat(probeThread.get()).isNotSameAs(caller)
    }

    @Test
    fun `switching project unsubscribes the previous one`() {
        capturingWatch()
        val state = DetectedTechState(health, scope)
        state.track(project)
        state.track(other)
        assertThat(unsubscribed).containsExactly(dir)
        state.stopIfTracking(project.id)
        assertThat(unsubscribed).containsExactly(dir)
        state.stopIfTracking(other.id)
        assertThat(unsubscribed).containsExactly(dir, otherDir)
    }

    @Test
    fun `refresh probes again off the calling thread`() {
        capturingWatch()
        val state = DetectedTechState(health, scope)
        state.track(project)
        await { runCatching { verify(health).checkNow(dir) }.isSuccess }
        state.refresh(project)
        await { runCatching { verify(health, org.mockito.Mockito.times(2)).checkNow(dir) }.isSuccess }
    }

    @Test
    fun `a group is watched and probed like a project`() {
        val group = Project(2, "g", dir, Instant.EPOCH, null, true, null)
        val state = DetectedTechState(health, scope)
        state.track(group)
        state.refresh(group)
        verify(health, org.mockito.Mockito.timeout(5000).times(2)).checkNow(dir)
        verify(health).watch(eq(dir), any())
    }

    @Test
    fun `a failing probe is swallowed`() {
        capturingWatch()
        doAnswer { throw IllegalStateException("io") }.`when`(health).checkNow(dir)
        val state = DetectedTechState(health, scope)
        state.track(project)
        verify(health, org.mockito.Mockito.timeout(5000)).checkNow(dir)
        await { scope.coroutineContext.job.children.none { it.isActive } }
        assertThat(state.of(1)).isEmpty()
    }

    @Test
    fun `without a backend nothing happens`() {
        val state = DetectedTechState(null, scope)
        state.track(project)
        state.refresh(project)
        assertThat(state.of(1)).isEmpty()
    }
}
