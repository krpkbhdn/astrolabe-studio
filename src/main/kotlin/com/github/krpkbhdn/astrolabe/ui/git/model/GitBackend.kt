package com.github.krpkbhdn.astrolabe.ui.git.model

import com.github.krpkbhdn.astrolabe.git.GitBranchService
import com.github.krpkbhdn.astrolabe.git.GitConflictService
import com.github.krpkbhdn.astrolabe.git.GitHistoryService
import com.github.krpkbhdn.astrolabe.git.GitIndexService
import com.github.krpkbhdn.astrolabe.git.GitLogService
import com.github.krpkbhdn.astrolabe.git.GitStashService
import com.github.krpkbhdn.astrolabe.git.GitStatusService
import com.github.krpkbhdn.astrolabe.git.GitSyncService
import com.github.krpkbhdn.astrolabe.git.GitWatcher
import com.github.krpkbhdn.astrolabe.git.TaskWorktreeService
import com.github.krpkbhdn.astrolabe.git.GitWorktreeService
import com.github.krpkbhdn.astrolabe.github.GhCli
import com.github.krpkbhdn.astrolabe.github.GhProperties
import com.github.krpkbhdn.astrolabe.git.ChangeDescriptionService
import com.github.krpkbhdn.astrolabe.github.PullRequestService
import com.github.krpkbhdn.astrolabe.gitlab.GlabCli
import com.github.krpkbhdn.astrolabe.gitlab.MergeRequestService
import org.springframework.context.ConfigurableApplicationContext

/** Every git service the Git tab talks to; all calls block, so callers stay off the UI thread. */
class GitBackend(
    val status: GitStatusService,
    val index: GitIndexService,
    val branches: GitBranchService,
    val sync: GitSyncService,
    val log: GitLogService,
    val stashes: GitStashService,
    val history: GitHistoryService,
    val conflicts: GitConflictService,
    val worktrees: GitWorktreeService,
    val watcher: GitWatcher,
    val taskWorktrees: TaskWorktreeService,
    val forge: PullRequestPort = PullRequestPort.ABSENT,
) {
    companion object {
        fun from(context: ConfigurableApplicationContext) = GitBackend(
            status = context.getBean(GitStatusService::class.java),
            index = context.getBean(GitIndexService::class.java),
            branches = context.getBean(GitBranchService::class.java),
            sync = context.getBean(GitSyncService::class.java),
            log = context.getBean(GitLogService::class.java),
            stashes = context.getBean(GitStashService::class.java),
            history = context.getBean(GitHistoryService::class.java),
            conflicts = context.getBean(GitConflictService::class.java),
            worktrees = context.getBean(GitWorktreeService::class.java),
            watcher = context.getBean(GitWatcher::class.java),
            taskWorktrees = context.getBean(TaskWorktreeService::class.java),
            forge = PullRequestPort.of(
                context.getBean(GhCli::class.java), context.getBean(PullRequestService::class.java),
                context.getBean(GlabCli::class.java), context.getBean(MergeRequestService::class.java),
                context.getBean(ChangeDescriptionService::class.java), context.getBean(GhProperties::class.java),
            ),
        )
    }
}
