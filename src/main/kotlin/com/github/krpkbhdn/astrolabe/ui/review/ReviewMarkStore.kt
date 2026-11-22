package com.github.krpkbhdn.astrolabe.ui.review

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.github.krpkbhdn.astrolabe.review.MarkDraft
import com.github.krpkbhdn.astrolabe.review.ReviewService
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MILLIS = 400L

/** Per-image mark lists with debounced saving, split from [ReviewState] so the write rules (dirty flag, never-cancelled write) live in one place. */
internal class ReviewMarkStore(
    private val service: ReviewService,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
) {
    private val markLists = ConcurrentHashMap<Long, SnapshotStateList<MarkDraft>>()
    private val dirty = ConcurrentHashMap.newKeySet<Long>()
    private val loadedMarks = ConcurrentHashMap.newKeySet<Long>()
    private val saveJobs = ConcurrentHashMap<Long, Job>()
    private val writeLock = Mutex()

    fun marks(imageId: Long): SnapshotStateList<MarkDraft> = markLists.computeIfAbsent(imageId) {
        mutableStateListOf<MarkDraft>().also {
            scope.launch { runGuarded(reportError) { loadMarks(imageId) } }
        }
    }

    fun forget(imageId: Long) {
        markLists.remove(imageId)
        loadedMarks.remove(imageId)
        dirty.remove(imageId)
        saveJobs.remove(imageId)?.cancel()
    }

    fun setComment(imageId: Long, index: Int, text: String) {
        val list = marks(imageId)
        if (index !in list.indices) return
        val old = list[index]
        list[index] = MarkDraft(old.kind, old.x, old.y, old.w, old.h, text, old.points)
        persist(imageId)
    }

    fun updateMark(imageId: Long, index: Int, edited: MarkDraft) {
        val list = marks(imageId)
        if (index !in list.indices) return
        list[index] = MarkDraft(edited.kind, edited.x, edited.y, edited.w, edited.h, list[index].comment, edited.points)
        persist(imageId)
    }

    /** Puts the stored marks before the ones added meanwhile, once; throws when the read fails so the caller can retry. */
    private suspend fun loadMarks(imageId: Long) {
        if (imageId in loadedMarks) return
        val stored = io { service.marks(imageId) }.map { MarkDraft(it.kind, it.x, it.y, it.w, it.h, it.comment, it.points) }
        if (loadedMarks.add(imageId)) marks(imageId).addAll(0, stored)
    }

    fun persist(imageId: Long) {
        dirty += imageId
        saveJobs[imageId]?.cancel()
        saveJobs[imageId] = scope.launch {
            delay(SAVE_DEBOUNCE_MILLIS)
            runGuarded(reportError) { writeIfDirty(imageId) }
        }
    }

    /** Never cancelled once started; the dirty flag stays set until the write succeeded. */
    private suspend fun writeIfDirty(imageId: Long) = withContext(NonCancellable) {
        writeLock.withLock {
            if (imageId !in dirty) return@withLock
            loadMarks(imageId)
            dirty.remove(imageId)
            val snapshot = marks(imageId).toList()
            var written = false
            try {
                io { service.saveMarks(imageId, snapshot) }
                written = true
            } finally {
                if (!written) dirty += imageId
            }
        }
    }

    /** Waits for running writes, then writes every remaining dirty image; the first failure is rethrown after all were tried. */
    suspend fun flush() {
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        var failure: RuntimeException? = null
        dirty.toList().forEach { id ->
            try {
                writeIfDirty(id)
            } catch (e: RuntimeException) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }
}
