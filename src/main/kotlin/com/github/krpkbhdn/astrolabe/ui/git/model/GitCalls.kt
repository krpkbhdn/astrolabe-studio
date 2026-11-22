package com.github.krpkbhdn.astrolabe.ui.git.model

import com.github.krpkbhdn.astrolabe.git.GitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val UNEXPECTED_ERROR = "Something went wrong"
internal const val GIT_BUSY = "Git is busy"

/** Wraps anything that is not already a [GitException] so the UI has one failure type to show. */
internal fun asGitException(e: RuntimeException): GitException =
    e as? GitException ?: GitException(GitException.Kind.OTHER, e.message ?: UNEXPECTED_ERROR, "")

/** Runs a blocking git [block] off the UI thread; a failure goes to [onError] and the result is null. */
internal suspend fun <T> gitCall(onError: (GitException) -> Unit, block: () -> T): T? =
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        onError(asGitException(e))
        null
    }
