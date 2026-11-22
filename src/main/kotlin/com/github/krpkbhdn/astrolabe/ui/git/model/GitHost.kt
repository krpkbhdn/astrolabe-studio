package com.github.krpkbhdn.astrolabe.ui.git.model

import com.github.krpkbhdn.astrolabe.notification.NotificationKind
import java.nio.file.Path

/** What [GitState] needs from the app around it: remembered preferences and a way to tell the user things. */
class GitHost(
    val prefs: () -> GitPrefs,
    val updatePrefs: ((GitPrefs) -> GitPrefs) -> Unit,
    val notify: (NotificationKind, String, String) -> Unit,
    /** Shows [directory], a worktree of [projectId], and optionally starts a session there under that project. */
    val showWorktree: (projectId: Long, directory: Path, launchSession: Boolean) -> Unit = { _, _, _ -> },
    /** In-memory part of forgetting a removed worktree; the stored part is [forgetStoredWorktree]. */
    val forgetWorktree: (directory: Path) -> Unit = {},
    val stopWorktreeWork: (directory: Path) -> Unit = {},
    val hasRunningWork: (directory: Path) -> Boolean = { false },
    /** True while processes of closed sessions and shells are still being ended, so their folders may still be locked. */
    val workStopping: () -> Boolean = { false },
    val openDirectory: (directory: Path) -> Unit = {},
    /** These three touch the database: call them off the UI thread. */
    val deleteRunConfigurations: (directory: Path) -> Unit = {},
    val runConfigurationNames: (directory: Path) -> List<String> = { emptyList() },
    val forgetStoredWorktree: (directory: Path) -> Unit = {},
    /** Waits [millis] before the retry of a removal Windows still holds; tests replace it so they do not sleep. */
    val unlockPause: (millis: Long) -> Unit = Thread::sleep,
)

/** The running mutation: what it does and the latest progress line git printed. */
data class GitJob(val label: String, val progress: String? = null)

/** A file picked in the Changes view; [staged] says which of its two diffs is shown. */
data class ChangeRef(val path: String, val staged: Boolean)
