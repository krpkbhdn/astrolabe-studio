package com.github.krpkbhdn.astrolabe.ui.codemode

import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeMode
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService
import com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess
import com.github.krpkbhdn.astrolabe.ui.await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong

class ProjectCodeModeStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `rapid changes are saved one after the other and a stale save does not overwrite the newer state`() {
        val service = mock(ProjectCodeModeService::class.java)
        val firstSaveRunning = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val saved = CopyOnWriteArrayList<List<String>>()
        val running = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        val shownAtSecondSave = AtomicReference<List<String>>()
        lateinit var state: ProjectCodeModeState
        `when`(service.save(anyLong(), anyBoolean(), anyList(), org.mockito.ArgumentMatchers.any(ToolCallAccess::class.java) ?: ToolCallAccess.ASK)).thenAnswer { call ->
            val servers = call.getArgument<List<String>>(2)
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            saved += servers
            if (saved.size == 1) {
                firstSaveRunning.countDown()
                releaseFirstSave.await()
            } else {
                shownAtSecondSave.set(state.servers)
            }
            running.decrementAndGet()
            ProjectCodeMode(1, call.getArgument(1), servers, ToolCallAccess.ASK)
        }
        state = ProjectCodeModeState(service, 1, scope) { }

        state.switchOn(true)
        assertThat(firstSaveRunning.await(5, TimeUnit.SECONDS)).isTrue()
        state.setServer("jira", true)
        releaseFirstSave.countDown()

        await { saved.size == 2 && shownAtSecondSave.get() != null && running.get() == 0 }
        assertThat(saved).containsExactly(emptyList(), listOf("jira"))
        assertThat(mostAtOnce).hasValue(1)
        assertThat(shownAtSecondSave.get()).containsExactly("jira")
    }

    @Test
    fun `a change made while the first load is still running is not overwritten by the loaded value`() {
        val service = mock(ProjectCodeModeService::class.java)
        val loading = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val saved = CountDownLatch(1)
        `when`(service.get(anyLong())).thenAnswer {
            loading.countDown()
            releaseLoad.await()
            ProjectCodeMode(1, false, emptyList(), ToolCallAccess.ASK)
        }
        `when`(service.save(anyLong(), anyBoolean(), anyList(), org.mockito.ArgumentMatchers.any(ToolCallAccess::class.java) ?: ToolCallAccess.ASK)).thenAnswer { call ->
            saved.countDown()
            ProjectCodeMode(1, call.getArgument(1), call.getArgument(2), call.getArgument(3))
        }
        val state = ProjectCodeModeState(service, 1, scope) { }

        state.load()
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue()
        state.switchOn(true)
        assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue()
        releaseLoad.countDown()

        await { scope.coroutineContext.job.children.none { it.isActive } }
        assertThat(state.enabled).isTrue()
    }

    @Test
    fun `a service change callback reloads the stored value and dispose unsubscribes`() {
        val service = mock(ProjectCodeModeService::class.java)
        val callback = AtomicReference<Runnable>()
        val unsubscribed = AtomicInteger()
        `when`(service.subscribe(org.mockito.ArgumentMatchers.any())).thenAnswer { call ->
            callback.set(call.getArgument(0))
            Runnable { unsubscribed.incrementAndGet() }
        }
        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, false, emptyList(), ToolCallAccess.ASK))
        val state = ProjectCodeModeState(service, 1, scope) { }
        state.load()
        await { state.loaded }
        assertThat(state.enabled).isFalse()

        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("x"), ToolCallAccess.ASK))
        callback.get().run()

        await { state.enabled }
        assertThat(state.servers).containsExactly("x")
        state.dispose()
        assertThat(unsubscribed).hasValue(1)
    }

    @Test
    fun `a group without its own setting shows the members merge read-only and override saves it as its own`() {
        val service = mock(ProjectCodeModeService::class.java)
        `when`(service.effective(7)).thenReturn(ProjectCodeMode(7, true, listOf("github"), ToolCallAccess.ASK))
        `when`(service.inherits(7)).thenReturn(true)
        val state = ProjectCodeModeState(service, 7, scope, { emptyList() }, true, { listOf("api" to listOf("github")) }) { }
        state.load()
        await { state.loaded }

        assertThat(state.inherits).isTrue()
        assertThat(state.servers).containsExactly("github")
        assertThat(state.sources["github"]).containsExactly("api")
        state.setServer("jira", true)
        assertThat(state.servers).containsExactly("github")
        verify(service, never()).save(anyLong(), anyBoolean(), anyList(), org.mockito.ArgumentMatchers.any(ToolCallAccess::class.java) ?: ToolCallAccess.ASK)

        `when`(service.inherits(7)).thenReturn(false)
        state.override()
        await { !state.inherits }
        verify(service).override(7)
    }

    @Test
    fun `a load that finds no change adopts the stored value`() {
        val service = mock(ProjectCodeModeService::class.java)
        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("a"), ToolCallAccess.ASK))
        val state = ProjectCodeModeState(service, 1, scope) { }

        state.load()

        await { state.loaded }
        assertThat(state.enabled).isTrue()
        assertThat(state.servers).containsExactly("a")
    }

    @Test
    fun `allow all is saved with the rest of the setting and adopted from the store`() {
        val service = mock(ProjectCodeModeService::class.java)
        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("a"), ToolCallAccess.ALL))
        `when`(service.save(anyLong(), anyBoolean(), anyList(), org.mockito.ArgumentMatchers.any(ToolCallAccess::class.java) ?: ToolCallAccess.ASK)).thenAnswer { call ->
            ProjectCodeMode(1, call.getArgument(1), call.getArgument(2), call.getArgument(3))
        }
        val state = ProjectCodeModeState(service, 1, scope) { }

        state.load()
        await { state.loaded && state.toolCalls == ToolCallAccess.ALL }
        state.chooseToolCalls(ToolCallAccess.ASK)

        await { state.toolCalls == ToolCallAccess.ASK && state.enabled }
        org.mockito.Mockito.verify(service, org.mockito.Mockito.timeout(5000)).save(1, true, listOf("a"), ToolCallAccess.ASK)
    }
}
