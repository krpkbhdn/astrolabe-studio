package com.github.krpkbhdn.astrolabe.ui.run

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.claude.launch.LaunchedSession
import com.github.krpkbhdn.astrolabe.shell.LaunchedShell
import com.github.krpkbhdn.astrolabe.run.ActiveRun
import com.github.krpkbhdn.astrolabe.run.RunConfiguration
import com.github.krpkbhdn.astrolabe.run.RunConfigurationService
import com.github.krpkbhdn.astrolabe.run.RunService
import com.github.krpkbhdn.astrolabe.ui.await
import com.github.krpkbhdn.astrolabe.ui.state.TerminalPort
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalInt
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RunStateTest {
    private val directory = Path.of("work", "app").toAbsolutePath()
    private val configurations = mock(RunConfigurationService::class.java)
    private val runs = mock(RunService::class.java)
    private val events = CopyOnWriteArrayList<String>()
    private val terminal = object : TerminalPort {
        override fun open(session: LaunchedSession) { events += "claude:${session.astrolabeSessionId()}" }
        override fun openShell(shell: LaunchedShell) { events += "open:${shell.terminalId()}" }
        override fun send(astrolabeSessionId: String, text: String) = Unit
        override fun close(astrolabeSessionId: String) { events += "close:$astrolabeSessionId" }

        @Composable
        override fun View(astrolabeSessionId: String, modifier: Modifier) = Unit
    }
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val state = RunState(configurations, runs, terminal, scope, { directory }, { }, { }, { })
    private var configurationList = listOf(RunConfiguration(7, 1, "dev", "npm run dev", directory))
    private var activeRun: ActiveRun? = null

    init {
        `when`(configurations.list(anyLong())).thenAnswer { configurationList }
        `when`(runs.current(anyLong())).thenAnswer { Optional.ofNullable(activeRun) }
    }

    @AfterEach
    fun stop() = scope.cancel()

    private fun runWithId(runId: String) = ActiveRun(7, runId, "dev", null, OptionalInt.empty())

    private fun reload() = state.track(1)

    private fun awaitEvents(vararg expected: String) {
        await { events.toList() == expected.toList() }
        assertThat(events).containsExactly(*expected)
    }

    @Test
    fun `a run started elsewhere gets a console`() {
        activeRun = runWithId("run-7-1")
        reload()
        awaitEvents("open:run-7-1")
    }

    @Test
    fun `a restart opens the new console and closes the old one`() {
        activeRun = runWithId("run-7-1")
        reload()
        awaitEvents("open:run-7-1")
        activeRun = runWithId("run-7-2")
        reload()
        awaitEvents("open:run-7-1", "open:run-7-2", "close:run-7-1")
    }

    @Test
    fun `a deleted configuration closes its console`() {
        activeRun = runWithId("run-7-1")
        reload()
        awaitEvents("open:run-7-1")
        configurationList = emptyList()
        reload()
        awaitEvents("open:run-7-1", "close:run-7-1")
    }

    @Test
    fun `runningIn matches the directory however it is spelled and ignores other folders`() {
        activeRun = runWithId("run-7-1")
        reload()
        awaitEvents("open:run-7-1")

        assertThat(state.runningIn(directory.resolve("..").resolve("app"))).hasSize(1)
        assertThat(state.runningIn(directory.resolve("other"))).isEmpty()
    }

    @Test
    fun `a finished run is not running work`() {
        activeRun = ActiveRun(7, "run-7-1", "dev", null, OptionalInt.of(0))
        reload()
        awaitEvents("open:run-7-1")

        assertThat(state.runningIn(directory)).isEmpty()
    }
}
