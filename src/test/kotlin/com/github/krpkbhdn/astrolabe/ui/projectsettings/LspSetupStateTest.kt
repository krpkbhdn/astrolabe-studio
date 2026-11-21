package com.github.krpkbhdn.astrolabe.ui.projectsettings

import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupService
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Kind
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Outcome
import com.github.krpkbhdn.astrolabe.ui.await
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LspSetupStateTest {
    private val AWAIT_MILLIS = 5_000L
    private val dir = Path.of("project")
    private val project = Project(1, "p", dir, Instant.EPOCH, null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = mock(LspSetupService::class.java)
    private val finished = CopyOnWriteArrayList<Project>()
    @Volatile private var installRunning = false
    private val pending = listOf(LspSetupStep(Kind.SERVER, "a", Outcome.PENDING, "Install A"))
    private val done = listOf(LspSetupStep(Kind.SERVER, "a", Outcome.DONE, "ok"))

    @AfterEach
    fun stop() = scope.cancel()

    private fun state() = LspSetupState(service, scope, { installRunning }, { finished += it }) { _, _ -> }

    @Test
    fun `setup walks through confirm, running and done and then refreshes the project`() {
        val release = CountDownLatch(1)
        `when`(service.plan(dir)).thenReturn(pending)
        `when`(service.run(any(), any(), any())).thenAnswer {
            release.await()
            done
        }
        val setup = state()

        setup.start(project)
        await { setup.flow is LspSetupFlow.Confirm }
        setup.confirm()
        assertThat(setup.running).isTrue()
        setup.close()
        assertThat(setup.flow).isInstanceOf(LspSetupFlow.Running::class.java)
        release.countDown()
        await { setup.flow is LspSetupFlow.Done }
        await { finished.isNotEmpty() }

        assertThat((setup.flow as LspSetupFlow.Done).results).isEqualTo(done)
        assertThat(finished).containsExactly(project)
        setup.close()
        assertThat(setup.flow).isEqualTo(LspSetupFlow.Idle)
    }

    @Test
    fun `an empty plan or one with nothing pending shows no dialog`() {
        `when`(service.plan(dir)).thenReturn(listOf(LspSetupStep(Kind.SERVER, "a", Outcome.MANUAL, "by hand")))
        val setup = state()

        setup.start(project)
        await { setup.plannedFor(1).isNotEmpty() }

        assertThat(setup.flow).isEqualTo(LspSetupFlow.Idle)
    }

    @Test
    fun `start is refused while a tool install runs`() {
        `when`(service.plan(dir)).thenReturn(pending)
        installRunning = true
        val setup = state()

        setup.start(project)

        assertThat(setup.flow).isEqualTo(LspSetupFlow.Idle)
        verify(service, never()).plan(any())
    }

    @Test
    fun `without a service nothing happens`() {
        val setup = LspSetupState(null, scope, { false }, { finished += it }) { _, _ -> }
        setup.start(project)
        setup.loadPlan(project)
        setup.confirm()
        setup.cancel()
        assertThat(setup.flow).isEqualTo(LspSetupFlow.Idle)
        assertThat(setup.plannedFor(1)).isEmpty()
    }

    @Test
    fun `a group loads a plan like a project`() {
        val group = Project(2, "g", dir, Instant.EPOCH, null, true, null)
        val setup = state()
        setup.loadPlan(group)
        verify(service, timeout(AWAIT_MILLIS)).plan(dir)
    }

    @Test
    fun `a second start while the confirm is open keeps the first flow`() {
        `when`(service.plan(dir)).thenReturn(pending)
        val setup = state()
        setup.start(project)
        await { setup.flow is LspSetupFlow.Confirm }
        val first = setup.flow
        setup.start(project)
        assertThat(setup.flow).isSameAs(first)
    }

    @Test
    fun `confirm is refused while a tool install runs and runs the service once otherwise`() {
        `when`(service.plan(dir)).thenReturn(pending)
        `when`(service.run(any(), any(), any())).thenReturn(done)
        val setup = state()
        setup.start(project)
        await { setup.flow is LspSetupFlow.Confirm }
        installRunning = true
        setup.confirm()
        assertThat(setup.flow).isInstanceOf(LspSetupFlow.Confirm::class.java)
        installRunning = false
        setup.confirm()
        setup.confirm()
        await { setup.flow is LspSetupFlow.Done }
        verify(service, org.mockito.Mockito.times(1)).run(any(), any(), any())
    }

    @Test
    fun `a refused confirm shows a notice that close clears`() {
        `when`(service.plan(dir)).thenReturn(pending)
        val setup = state()
        setup.start(project)
        await { setup.flow is LspSetupFlow.Confirm }
        installRunning = true
        setup.confirm()
        assertThat(setup.notice).isNotBlank()
        setup.close()
        assertThat(setup.notice).isNull()
    }

    @Test
    fun `an older plan result never replaces a newer one and plans are kept per project`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        `when`(service.plan(dir)).thenAnswer {
            entered.countDown()
            release.await()
            pending
        }.thenReturn(done)
        val other = Project(3, "o", Path.of("other"), Instant.EPOCH, null)
        `when`(service.plan(other.path)).thenReturn(pending)
        val setup = state()
        setup.loadPlan(project)
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        setup.loadPlan(project)
        await { setup.plannedFor(1) == done }
        release.countDown()
        await { scope.coroutineContext.job.children.none { it.isActive } }
        assertThat(setup.plannedFor(1)).isEqualTo(done)
        setup.loadPlan(other)
        await { setup.plannedFor(3) == pending }
        assertThat(setup.plannedFor(1)).isEqualTo(done)
    }

    @Test
    fun `a failing plan load leaves an empty plan`() {
        `when`(service.plan(dir)).thenReturn(pending).thenThrow(IllegalStateException("io"))
        val setup = state()
        setup.loadPlan(project)
        await { setup.plannedFor(1).isNotEmpty() }
        setup.loadPlan(project)
        await { setup.plannedFor(1).isEmpty() }
    }

    @Test
    fun `a failing run reports the error, returns to idle and still refreshes`() {
        val errors = CopyOnWriteArrayList<String>()
        `when`(service.plan(dir)).thenReturn(pending)
        `when`(service.run(any(), any(), any())).thenThrow(IllegalStateException("boom"))
        val setup = LspSetupState(service, scope, { false }, { finished += it }) { title, _ -> errors += title }
        setup.start(project)
        await { setup.flow is LspSetupFlow.Confirm }
        setup.confirm()
        await { errors.isNotEmpty() }
        await { finished.isNotEmpty() }
        assertThat(setup.flow).isEqualTo(LspSetupFlow.Idle)
    }

    @Test
    fun `cancel is passed to the service`() {
        state().cancel()

        verify(service).cancel()
    }
}
