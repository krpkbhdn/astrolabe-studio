package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.launch.BackgroundAgent
import com.github.krpkbhdn.astrolabe.ui.await
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

private const val PROJECT_ID = 1L
private const val OTHER_PROJECT_ID = 2L
class BackgroundAgentsStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val release = CountDownLatch(1)
    private val calls = AtomicInteger()
    private val agent = BackgroundAgent("a1", "build", "running", "compiling")
    private val path = Path.of("project")

    @AfterEach
    fun stop() {
        release.countDown()
        scope.cancel()
    }

    @Test
    fun `refresh stores the listed agents of the project`() {
        val state = BackgroundAgentsState(scope, { listOf(agent) }) {}

        state.refresh(PROJECT_ID, path)
        await { state[PROJECT_ID] != null }

        assertThat(state[PROJECT_ID]).containsExactly(agent)
        assertThat(state[OTHER_PROJECT_ID]).isNull()
    }

    @Test
    fun `refresh while one is running for the same project is skipped`() {
        val state = BackgroundAgentsState(scope, { calls.incrementAndGet(); release.await(); listOf(agent) }) {}

        state.refresh(PROJECT_ID, path)
        await { calls.get() == 1 }
        state.refresh(PROJECT_ID, path)
        release.countDown()
        await { state[PROJECT_ID] != null }

        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `retain drops agents of removed projects`() {
        val state = BackgroundAgentsState(scope, { listOf(agent) }) {}
        state.refresh(PROJECT_ID, path)
        state.refresh(OTHER_PROJECT_ID, path)
        await { state[PROJECT_ID] != null && state[OTHER_PROJECT_ID] != null }

        state.retain(setOf(PROJECT_ID))

        assertThat(state[PROJECT_ID]).isNotNull()
        assertThat(state[OTHER_PROJECT_ID]).isNull()
    }

    @Test
    fun `refresh finishing after its project was retained away is not stored`() {
        val state = BackgroundAgentsState(scope, { calls.incrementAndGet(); release.await(); listOf(agent) }) {}

        state.refresh(PROJECT_ID, path)
        await { calls.get() == 1 }
        state.retain(setOf(OTHER_PROJECT_ID))
        release.countDown()
        await { scope.coroutineContext[Job]!!.children.none() }

        assertThat(state[PROJECT_ID]).isNull()
    }

    @Test
    fun `a failing refresh is reported and allows the next one`() {
        val errors = mutableListOf<String>()
        val state = BackgroundAgentsState(scope, { if (calls.incrementAndGet() == 1) error("boom") else listOf(agent) }) { errors += it }

        state.refresh(PROJECT_ID, path)
        await { errors.isNotEmpty() }
        await { scope.coroutineContext[Job]!!.children.none() }
        state.refresh(PROJECT_ID, path)
        await { state[PROJECT_ID] != null }

        assertThat(errors).containsExactly("boom")
    }
}
