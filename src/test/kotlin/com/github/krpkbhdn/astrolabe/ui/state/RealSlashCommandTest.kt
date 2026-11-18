package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.AstrolabeApplication
import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher
import com.github.krpkbhdn.astrolabe.claude.launch.LaunchMode
import com.github.krpkbhdn.astrolabe.claude.launch.LaunchedSession
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry
import com.github.krpkbhdn.astrolabe.project.ModelSelection
import com.github.krpkbhdn.astrolabe.project.ProjectService
import com.github.krpkbhdn.astrolabe.usage.SessionUsageService
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private const val WAIT_MILLIS = 90_000L
private const val SETTLE_MILLIS = 3_000L
private const val ENTER_KEY = "\r"
private val userSettings: Path = Path.of(System.getProperty("user.home"), ".claude", "settings.json")

/**
 * Real claude, one tiny prompt: the project's selection reaches a launched session as flags, and the same writer the UI uses
 * (text, pause, Enter) switches model and effort with /model and /effort, which the statusLine reports. Claude saves those
 * as the user's defaults, so the test restores ~/.claude/settings.json byte for byte.
 */
class RealSlashCommandTest {
    @TempDir
    lateinit var dataDir: Path

    private class PtyPort(val sessions: MutableMap<String, LaunchedSession>) : TerminalPort {
        override fun open(session: LaunchedSession) { sessions[session.astrolabeSessionId()] = session }
        override fun send(astrolabeSessionId: String, text: String) {
            sessions.getValue(astrolabeSessionId).process().outputStream.apply { write(text.toByteArray()); flush() }
        }
        override fun close(astrolabeSessionId: String) = Unit

        @Composable
        override fun View(astrolabeSessionId: String, modifier: Modifier) = Unit
    }

    @Test
    fun launchFlagsThenSlashCommandsReachTheRunningSession() {
        assumeTrue(System.getenv("ASTROLABE_REAL_CLAUDE") == "1")
        val settingsBefore = if (Files.exists(userSettings)) Files.readAllBytes(userSettings) else null
        try {
            AstrolabeApplication.start(arrayOf("--astrolabe.data-dir=$dataDir")).use { context ->
                val projects = context.getBean(ProjectService::class.java)
                val project = projects.add(Path.of("").toAbsolutePath())
                projects.selectModel(project.id(), ModelSelection("haiku", "low"))
                val registry = context.getBean(SessionRegistry::class.java)
                val usage = context.getBean(SessionUsageService::class.java)
                val session = context.getBean(ClaudeLauncher::class.java).launch(project.id(), LaunchMode.NEW)
                drain(session)
                val port = PtyPort(mutableMapOf(session.astrolabeSessionId() to session))
                val writer = SessionCommandWriter(port)
                val id = session.astrolabeSessionId()
                await("session registered") { registry.find(id).map { it.claudeSessionId() != null }.orElse(false) }
                Thread.sleep(SETTLE_MILLIS)
                assertThat(runBlocking { writer.run(id, "say hi in two words") }).isTrue()
                await("turn started") { status(registry, id) == AgentStatus.WORKING }
                await("turn finished") { status(registry, id) == AgentStatus.IDLE && usage.of(id).isPresent }
                println("LAUNCHED_WITH ${usage.of(id).get().modelName()} ${usage.of(id).get().effort()}")
                assertThat(usage.of(id).get().modelName()).containsIgnoringCase("haiku")
                assertThat(usage.of(id).get().effort()).isEqualTo("low")

                assertThat(runBlocking { writer.run(id, modelCommand("sonnet")) }).isTrue()
                Thread.sleep(SETTLE_MILLIS)
                port.send(id, ENTER_KEY) // with history claude asks "Switch model?" first, option 1 is preselected
                await("model switched") { modelReported(usage.of(id).get().modelName(), "sonnet") }
                assertThat(runBlocking { writer.run(id, effortCommand("high")) }).isTrue()
                await("effort switched") { usage.of(id).get().effort() == "high" }
                println("AFTER_COMMANDS ${usage.of(id).get().modelName()} ${usage.of(id).get().effort()}")
                assertThat(runBlocking { writer.run(id, "/exit") }).isTrue()
                await("exit") { registry.find(id).isEmpty }
            }
        } finally {
            if (settingsBefore != null) Files.write(userSettings, settingsBefore)
        }
        if (settingsBefore != null) assertThat(Files.readAllBytes(userSettings)).isEqualTo(settingsBefore)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertThat(condition()).describedAs("$what within ${WAIT_MILLIS}ms").isTrue()
    }
}

/** Nobody reads the PTY in a test, and an unread console output buffer eventually stalls claude's rendering. */
private fun drain(session: LaunchedSession) {
    val input = session.process().inputStream
    Thread { runCatching { val buffer = ByteArray(8192); while (input.read(buffer) >= 0) {} } }.apply { isDaemon = true }.start()
}

private fun status(registry: SessionRegistry, id: String): AgentStatus? = registry.find(id).map { it.status() }.orElse(null)
