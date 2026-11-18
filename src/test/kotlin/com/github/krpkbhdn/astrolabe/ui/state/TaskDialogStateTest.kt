package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.claude.launch.LaunchedSession
import com.github.krpkbhdn.astrolabe.git.TaskWorktree
import com.github.krpkbhdn.astrolabe.git.TaskWorktreeService
import com.github.krpkbhdn.astrolabe.project.ModelSelection
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.prompt.PromptTemplate
import com.github.krpkbhdn.astrolabe.ui.PreviewWorld
import com.github.krpkbhdn.astrolabe.ui.await
import com.github.krpkbhdn.astrolabe.ui.queue.QueueState
import com.github.krpkbhdn.astrolabe.ui.teams.TeamsState
import com.pty4j.PtyProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import com.github.krpkbhdn.astrolabe.taskattachment.TaskAttachmentProperties
import org.junit.jupiter.api.io.TempDir
import java.time.Instant
import androidx.compose.ui.text.TextRange
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TaskDialogStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<String>()
    private val createdFor = CopyOnWriteArrayList<Long>()
    private val queue = mock(QueueState::class.java)
    private val terminal = object : TerminalPort {
        override fun open(session: LaunchedSession) = Unit
        override fun send(astrolabeSessionId: String, text: String) = Unit
        override fun close(astrolabeSessionId: String) = Unit

        @Composable
        override fun View(astrolabeSessionId: String, modifier: Modifier) = Unit
    }
    private val world = PreviewWorld()
    private val backend = world.backend(terminal)
    private val worktrees = mock(TaskWorktreeService::class.java)
    private val worktree = TaskWorktree(5, 1, Path.of("repo-task-x"), "task/x", "main", "x")
    private val sessions = SessionsState(
        backend, scope, { error("not used") }, { null }, { null },
        onOpened = { _, id -> opened += id }, onClosed = { _, _ -> }, showProject = {}, requestConfirm = {}, reportError = { errors += it },
    )

    @TempDir
    lateinit var imageDir: Path

    @AfterEach
    fun stop() = scope.cancel()

    private fun tasks(limits: TaskAttachmentProperties = TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000)) = TaskDialogState(
        sessions, mock(TeamsState::class.java), queue, scope, worktrees, { errors += it }, { "main" },
        { _, sessionId -> "task/of-$sessionId" }, { createdFor += it }, TaskImages(imageDir, limits),
    )

    private fun project() = Project(1, "repo", Path.of("repo"), Instant.EPOCH, ModelSelection.DEFAULT)

    private fun TaskDialogState.type(text: String) = task.edit { replace(0, length, text); selection = TextRange(text.length) }

    private fun TaskDialogState.attach(number: Int, name: String): Path = Files.createFile(imageDir.resolve(name)).also { images[number] = it }

    @Test
    fun `removing the placeholder from the text drops the image and deletes its file`() {
        val tasks = tasks()
        val file = tasks.attach(1, "a.png")
        tasks.type("look [Image #1] here")

        tasks.onTextChanged()
        assertThat(file).exists()

        tasks.type("look  here")
        tasks.onTextChanged()

        assertThat(tasks.images).isEmpty()
        assertThat(file).doesNotExist()
    }

    @Test
    fun `the thumbnail remove button takes the placeholder out of the text and deletes the file`() {
        val tasks = tasks()
        val file = tasks.attach(2, "b.png")
        tasks.type("a [Image #2]b")

        tasks.removeImage(2)

        assertThat(tasks.task.text.toString()).isEqualTo("a b")
        assertThat(file).doesNotExist()
        assertThat(tasks.images).isEmpty()
    }

    @Test
    fun `backspace after a placeholder removes it whole and the caret skips over it`() {
        val tasks = tasks()
        tasks.attach(1, "c.png")
        tasks.type("x [Image #1]")
        tasks.onTextChanged()

        assertThat(tasks.deleteTokenAtCaret(backward = true)).isTrue()
        tasks.onTextChanged()

        assertThat(tasks.task.text.toString()).isEqualTo("x ")
        assertThat(tasks.images).isEmpty()
    }

    @Test
    fun `closing the dialog deletes attached images and clears the text`() {
        val tasks = tasks()
        val file = tasks.attach(1, "d.png")
        tasks.type("[Image #1]")

        tasks.close()

        assertThat(file).doesNotExist()
        assertThat(tasks.images).isEmpty()
        assertThat(tasks.task.text.toString()).isEmpty()
    }

    @Test
    fun `starting with only an image marker lists the file and keeps it for the session`() {
        val tasks = tasks()
        val file = tasks.attach(1, "e.png")
        tasks.type("[Image #1]")
        val prompt = taskPrompt("[Image #1]", emptyMap(), mapOf(1 to file))
        doReturn(LaunchedSession("astrolabe-2", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher).launchTask(1, prompt)

        tasks.start(project(), null, "[Image #1]", false, false)
        await { "astrolabe-2" in opened }

        assertThat(file).exists()
        assertThat(tasks.images).isEmpty()
        assertThat(tasks.task.text.toString()).isEmpty()
    }

    @Test
    fun `an isolated start seeds the worktree with the text without markers`() {
        val tasks = tasks()
        val file = tasks.attach(1, "f.png")
        `when`(worktrees.create(1, Path.of("repo"), "fix  this")).thenReturn(worktree)
        doReturn(LaunchedSession("astrolabe-4", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher)
            .launchTaskIn(1, worktree.path(), null, taskPrompt("fix [Image #1] this", emptyMap(), mapOf(1 to file)))

        tasks.start(project(), null, "fix [Image #1] this", false, true)

        await { "astrolabe-4" in opened }
        verify(worktrees).create(1, Path.of("repo"), "fix  this")
    }

    @Test
    fun `a full image list rejects another paste`() {
        val tasks = tasks(TaskAttachmentProperties(1, 1024, Duration.ofDays(7), 10, 1000))
        tasks.attach(1, "g.png")

        tasks.pasteImage(false)

        assertThat(tasks.imageHint).contains("At most 1")
        assertThat(tasks.images).hasSize(1)
    }

    @Test
    fun `a long paste becomes a numbered placeholder at the caret and starting expands it`() {
        val tasks = tasks()
        val long = (1..12).joinToString("\n") { "line $it" }
        tasks.type("do ")

        assertThat(tasks.pasteLongText("short")).isFalse()
        assertThat(tasks.pasteLongText(long)).isTrue()
        tasks.type(tasks.task.text.toString() + " now")
        assertThat(tasks.task.text.toString()).isEqualTo("do [Pasted text #1 +12 lines] now")
        doReturn(LaunchedSession("astrolabe-5", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher).launchTask(1, "do $long now")

        tasks.start(project(), null, tasks.task.text.toString(), false, false)

        await { "astrolabe-5" in opened }
    }

    @Test
    fun `a pasted text placeholder removed from the text is forgotten`() {
        val tasks = tasks()
        tasks.pasteLongText("a\n".repeat(12))

        tasks.type("")
        tasks.onTextChanged()
        tasks.pasteLongText("b\n".repeat(12))

        assertThat(tasks.task.text.toString()).isEqualTo("[Pasted text #2 +12 lines]")
    }


    @Test
    fun `backspace with a selection leaves tokens alone and a damaged token drops its image only`() {
        val tasks = tasks()
        val first = tasks.attach(1, "h.png")
        val second = tasks.attach(2, "i.png")
        tasks.type("[Image #1][Image #2]")
        tasks.task.edit { selection = TextRange(0, 3) }

        assertThat(tasks.deleteTokenAtCaret(backward = true)).isFalse()

        tasks.type("[Image #1][Image #2")
        tasks.onTextChanged()

        assertThat(first).exists()
        assertThat(second).doesNotExist()
        assertThat(tasks.images.keys).containsExactly(1)
    }

    @Test
    fun `two adjacent tokens are deleted one backspace at a time`() {
        val tasks = tasks()
        tasks.attach(1, "j.png")
        val second = tasks.attach(2, "k.png")
        tasks.type("[Image #1][Image #2]")

        assertThat(tasks.deleteTokenAtCaret(backward = true)).isTrue()
        tasks.onTextChanged()

        assertThat(tasks.task.text.toString()).isEqualTo("[Image #1]")
        assertThat(second).doesNotExist()
        assertThat(tasks.images.keys).containsExactly(1)
    }

    @Test
    fun `a pasted text token typed by hand with no stored text stays as typed`() {
        val tasks = tasks()
        doReturn(LaunchedSession("astrolabe-6", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher).launchTask(1, "[Pasted text #9 +12 lines]")

        tasks.start(project(), null, "[Pasted text #9 +12 lines]", false, false)

        await { "astrolabe-6" in opened }
    }

    @Test
    fun `a long paste replaces the selection and the number is not reused after removal`() {
        val tasks = tasks()
        val long = "x\n".repeat(12)
        tasks.type("abcdef")
        tasks.task.edit { selection = TextRange(1, 3) }

        tasks.pasteLongText(long)
        assertThat(tasks.task.text.toString()).isEqualTo("a[Pasted text #1 +12 lines]def")
        tasks.type("")
        tasks.onTextChanged()
        tasks.pasteLongText(long)
        tasks.pasteLongText(long)

        assertThat(tasks.task.text.toString()).isEqualTo("[Pasted text #2 +12 lines][Pasted text #3 +12 lines]")
    }


    @Test
    fun `an isolated task creates the worktree and starts the session in it under the parent project`() {
        `when`(worktrees.create(1, Path.of("repo"), "x")).thenReturn(worktree)
        doReturn(LaunchedSession("astrolabe-1", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher).launchTaskIn(1, worktree.path(), null, "x")
        val tasks = tasks()

        tasks.start(project(), null, "x", false, true)
        await { "astrolabe-1" in opened }

        verify(backend.launcher).launchTaskIn(1, worktree.path(), null, "x")
        assertThat(sessions.sessionDirectories["astrolabe-1"]).isEqualTo(worktree.path())
        assertThat(createdFor).containsExactly(1L)
        await { !tasks.starting }
    }

    @Test
    fun `a failed launch names the worktree that stays behind in a single message`() {
        `when`(worktrees.create(1, Path.of("repo"), "x")).thenReturn(worktree)
        doThrow(IllegalStateException("boom")).`when`(backend.launcher).launchTaskIn(1, worktree.path(), null, "x")
        val tasks = tasks()

        tasks.start(project(), null, "x", false, true)
        await { errors.any { "repo-task-x" in it } }

        await { !tasks.starting }
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).startsWith("boom")
    }

    @Test
    fun `starting is reset when creating the worktree throws a runtime exception`() {
        `when`(worktrees.create(1, Path.of("repo"), "x")).thenThrow(IllegalStateException("no space"))
        val tasks = tasks()

        tasks.start(project(), null, "x", false, true)

        await { errors == listOf("no space") }
        await { !tasks.starting }
    }

    @Test
    fun `starting is reset when creating the worktree throws something that is not a runtime exception`() {
        `when`(worktrees.create(1, Path.of("repo"), "x")).thenThrow(AssertionError("broken"))
        val tasks = tasks()

        tasks.start(project(), null, "x", false, true)

        await { !tasks.starting && errors.isNotEmpty() }
        assertThat(errors).containsExactly("broken")
    }

    @Test
    fun `a second start while one is being set up is ignored`() {
        val release = CountDownLatch(1)
        `when`(worktrees.create(1, Path.of("repo"), "x")).thenAnswer { release.await(); worktree }
        doReturn(LaunchedSession("astrolabe-1", 1, "claude", mock(PtyProcess::class.java))).`when`(backend.launcher).launchTaskIn(1, worktree.path(), null, "x")
        val tasks = tasks()

        tasks.start(project(), null, "x", false, true)
        await { tasks.starting }
        tasks.start(project(), null, "x", false, true)
        release.countDown()

        await { "astrolabe-1" in opened }
        await { !tasks.starting }
        verify(worktrees, times(1)).create(1, Path.of("repo"), "x")
    }

    @Test
    fun `a template queued for a session takes the branch of the folder that session runs in`() {
        val template = PromptTemplate(1, "t", "", "review {branch}", null, null)

        tasks().queueFromTemplate(project(), "astrolabe-9", template)

        verify(queue).add("astrolabe-9", "review task/of-astrolabe-9")
    }
}
