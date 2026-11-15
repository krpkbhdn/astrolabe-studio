package com.github.krpkbhdn.ccmanager.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.github.krpkbhdn.ccmanager.git.TaskWorktree
import com.github.krpkbhdn.ccmanager.git.TaskWorktreeService
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.prompt.PlaceholderSubstitution
import com.github.krpkbhdn.ccmanager.prompt.PlaceholderValues
import com.github.krpkbhdn.ccmanager.prompt.PromptTemplate
import com.github.krpkbhdn.ccmanager.team.Team
import com.github.krpkbhdn.ccmanager.ui.prompts.teamForTemplate
import com.github.krpkbhdn.ccmanager.ui.queue.QueueState
import com.github.krpkbhdn.ccmanager.ui.teams.TeamsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/** The start-task dialog and the two template pickers (quick start, queue), and what they do with the chosen text. */
class TaskDialogState(
    private val sessions: SessionsState,
    private val teams: TeamsState,
    private val queue: QueueState,
    private val scope: CoroutineScope,
    private val taskWorktrees: TaskWorktreeService,
    private val reportError: (String) -> Unit,
    private val branchOf: (Long) -> String?,
    private val branchOfSession: (projectId: Long, ccmSessionId: String) -> String? = { projectId, _ -> branchOf(projectId) },
    private val onWorktreeCreated: (projectId: Long) -> Unit = {},
    private val imageStore: TaskImages,
) {
    /** The task text of the dialog; the placeholders in it are the only thing that keeps an attachment alive. */
    val task = TextFieldState()

    /** Pasted images by placeholder number, as files. */
    val images = mutableStateMapOf<Int, Path>()
    private val pastedTexts = mutableMapOf<Int, String>()
    private var imageCounter = 0
    private var textCounter = 0
    private var lastSelection: TextRange? = null
    var imageHint by mutableStateOf<String?>(null)
        private set

    /** True while an isolated task is being set up, so a second Enter cannot create a second worktree. */
    var starting by mutableStateOf(false)
        private set
    var projectId by mutableStateOf<Long?>(null)
    var templateId by mutableStateOf<Long?>(null)
    var quickStartProjectId by mutableStateOf<Long?>(null)

    /** Project and live session whose queue receives the card chosen in the picker. */
    var queuePickTarget by mutableStateOf<Pair<Long, String>?>(null)

    fun open(projectId: Long, templateId: Long? = null) {
        this.templateId = templateId
        this.projectId = projectId
    }

    /** Closes the dialog; images that were attached but not started with are deleted, and the text and numbering start over. */
    fun close() {
        projectId = null
        templateId = null
        imageStore.delete(images.values.toList())
        images.clear()
        pastedTexts.clear()
        imageCounter = 0
        textCounter = 0
        lastSelection = null
        imageHint = null
        task.clearText()
    }

    /** Pastes the clipboard image off the UI thread and marks it `[Image #N]` at the caret; [onlyIfPresent] leaves a clipboard without an image silent. */
    fun pasteImage(onlyIfPresent: Boolean) {
        if (images.size >= imageStore.maxCount) { imageHint = "At most ${imageStore.maxCount} images"; return }
        scope.launch {
            val saved = try { io { imageStore.saveFromClipboard() } } catch (e: IllegalStateException) { imageHint = e.message; return@launch }
            if (saved != null) {
                val number = ++imageCounter
                images[number] = saved
                insertAtCaret(imageToken(number))
                imageHint = null
            } else if (!onlyIfPresent) imageHint = "The clipboard holds no image"
        }
    }

    fun clipboardHasOnlyImage() = imageStore.clipboardHasOnlyImage()

    /** Takes over a Ctrl/Cmd+V of a long text by inserting `[Pasted text #N +M lines]` and keeping the text aside; false leaves the normal paste to run. */
    fun pasteLongText(clipboard: String? = imageStore.clipboardText()): Boolean {
        val text = clipboard?.takeIf(imageStore::isLongPaste) ?: return false
        val number = ++textCounter
        pastedTexts[number] = text
        insertAtCaret(pastedTextToken(number, lineCount(text)))
        return true
    }

    /** Removes the placeholder of image [number] from the text, which drops the file. */
    fun removeImage(number: Int) {
        tokensIn(task.text.toString()).firstOrNull { it.kind == TokenKind.IMAGE && it.number == number }?.let { deleteRange(it.range) }
        reconcile()
    }

    /** Key press on the text field: Backspace and Delete next to a placeholder remove it whole. */
    fun deleteTokenAtCaret(backward: Boolean): Boolean {
        val range = atomicDeletion(task.selection, tokensIn(task.text.toString()), backward) ?: return false
        deleteRange(range)
        return true
    }

    /** Runs after every change of the text or caret: drops attachments whose placeholder is gone and keeps the caret out of placeholders. */
    fun onTextChanged() {
        reconcile()
        val snapped = snapSelection(task.selection, tokensIn(task.text.toString()), lastSelection)
        if (snapped != task.selection) task.edit { selection = snapped }
        lastSelection = task.selection
    }

    fun reconcile() {
        val live = tokensIn(task.text.toString())
        val liveImages = live.filter { it.kind == TokenKind.IMAGE }.map { it.number }.toSet()
        val gone = images.keys.filter { it !in liveImages }
        imageStore.delete(gone.mapNotNull { images[it] })
        gone.forEach { images.remove(it) }
        val liveTexts = live.filter { it.kind == TokenKind.PASTED_TEXT }.map { it.number }.toSet()
        pastedTexts.keys.retainAll(liveTexts)
    }

    private fun insertAtCaret(token: String) = task.edit {
        val from = selection.min
        replace(from, selection.max, token)
        selection = TextRange(from + token.length)
    }

    private fun deleteRange(range: TextRange) = task.edit {
        replace(range.start, range.end, "")
        selection = TextRange(range.start)
    }

    fun placeholderValuesOf(project: Project) = PlaceholderValues(project.name, branchOf(project.id), null)

    /** The branch is the one of the folder the session runs in, which may be a worktree. */
    private fun placeholderValuesOf(project: Project, ccmSessionId: String) = PlaceholderValues(project.name, branchOfSession(project.id, ccmSessionId), null)

    fun queueFromTemplate(project: Project, ccmSessionId: String, template: PromptTemplate) {
        queuePickTarget = null
        queue.add(ccmSessionId, PlaceholderSubstitution.apply(template.text, placeholderValuesOf(project, ccmSessionId)))
    }

    /** Starts [template] at once in [project], led by its default team or else the project's default team. */
    fun startFromTemplate(project: Project, template: PromptTemplate) {
        quickStartProjectId = null
        val team = teamForTemplate(template, teams.teams, teams.defaultTeamOf(project.id))
        start(project, team, PlaceholderSubstitution.apply(template.text, placeholderValuesOf(project)).trim(), false, false)
    }

    /** Starts a session with [task] as its first prompt, led by [team] or without a team, and opens its terminal. */
    fun start(project: Project, team: Team?, task: String, makeDefault: Boolean, isolated: Boolean) {
        val prompt = taskPrompt(task, pastedTexts, images)
        val seed = taskSeedText(task, pastedTexts).ifBlank { "image task" }
        val onStarted = {
            images.clear()
            close()
            if (makeDefault && team != null) teams.setProjectDefault(project.id, team.id)
        }
        if (isolated) startInWorktree(project, team, seed, prompt, onStarted) else sessions.launchTask(project.id, team, prompt, onStarted = { onStarted() })
    }

    /**
     * Creates branch task/<slug> in a new worktree and runs the task session there under the same project; further starts are ignored until this one ends, and a failure after the worktree exists says where it is.
     */
    private fun startInWorktree(project: Project, team: Team?, task: String, prompt: String, onStarted: () -> Unit) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                runGuarded({ starting = false; reportError(it) }) {
                    val created = io { taskWorktrees.create(project.id, project.path, task) }
                    onWorktreeCreated(project.id)
                    sessions.launchTaskIn(project.id, created.path(), team, prompt, { reason -> starting = false; reportError(strandedWorktreeMessage(created, reason)) }) { starting = false; onStarted() }
                }
            } catch (e: Throwable) {
                starting = false
                if (e !is CancellationException) reportError(e.message ?: "The task could not be started")
                throw e
            }
        }
    }
}

/** The worktree stays on disk when a later step fails, so the user must be told where it is. */
fun strandedWorktreeMessage(worktree: TaskWorktree, reason: String) =
    "$reason. The worktree ${worktree.path()} on branch ${worktree.branch()} was created and is still there; remove it in the Git tab's Worktrees view if you do not need it."
