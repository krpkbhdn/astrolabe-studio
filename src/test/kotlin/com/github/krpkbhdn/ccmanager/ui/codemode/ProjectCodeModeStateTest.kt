package com.github.krpkbhdn.ccmanager.ui.codemode

import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService
import com.github.krpkbhdn.ccmanager.ui.await
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
        `when`(service.save(anyLong(), anyBoolean(), anyList())).thenAnswer { call ->
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
            ProjectCodeMode(1, call.getArgument(1), servers)
        }
        state = ProjectCodeModeState(service, 1, scope) { }

        state.switchOn(true)
        assertThat(firstSaveRunning.await(5, TimeUnit.SECONDS)).isTrue()
        state.setServer("jira", true)
        releaseFirstSave.countDown()

        await { saved.size == 2 && state.servers == listOf("jira") && state.enabled }
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
            ProjectCodeMode(1, false, emptyList())
        }
        `when`(service.save(anyLong(), anyBoolean(), anyList())).thenAnswer { call ->
            saved.countDown()
            ProjectCodeMode(1, call.getArgument(1), call.getArgument(2))
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
        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, false, emptyList()))
        val state = ProjectCodeModeState(service, 1, scope) { }
        state.load()
        await { state.loaded }
        assertThat(state.enabled).isFalse()

        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("x")))
        callback.get().run()

        await { state.enabled }
        assertThat(state.servers).containsExactly("x")
        state.dispose()
        assertThat(unsubscribed).hasValue(1)
    }

    @Test
    fun `a load that finds no change adopts the stored value`() {
        val service = mock(ProjectCodeModeService::class.java)
        `when`(service.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("a")))
        val state = ProjectCodeModeState(service, 1, scope) { }

        state.load()

        await { state.loaded }
        assertThat(state.enabled).isTrue()
        assertThat(state.servers).containsExactly("a")
    }
}
