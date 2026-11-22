package com.github.krpkbhdn.astrolabe.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import com.github.krpkbhdn.astrolabe.review.MarkDraft
import com.github.krpkbhdn.astrolabe.review.Review
import com.github.krpkbhdn.astrolabe.review.ReviewImage
import com.github.krpkbhdn.astrolabe.review.ReviewService
import com.github.krpkbhdn.astrolabe.review.ReviewStatus
import com.github.krpkbhdn.astrolabe.team.Team
import com.github.krpkbhdn.astrolabe.ui.state.LiveSessions
import com.github.krpkbhdn.astrolabe.ui.state.Refresher
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

data class SessionChoice(val astrolabeSessionId: String, val label: String)

data class SendTarget(val review: Review, val candidates: List<SessionChoice>, val teams: TeamOptions = TeamOptions.NONE)

private const val FINAL_FLUSH_TIMEOUT_MILLIS = 2000L
private const val FULL_CACHE_SIZE = 3
private const val THUMBNAIL_CACHE_SIZE = 40
private const val START_FAILED = "Could not start a session"

/**
 * Visual review screen state: reviews per project, decoded screenshots, marks (saved debounced, numbered per image) and sending the feedback to a session.
 * Service calls run off the UI thread.
 */
class ReviewState(
    private val service: ReviewService,
    private val live: () -> LiveSessions,
    private val enqueue: (astrolabeSessionId: String, text: String) -> Unit,
    private val startSession: (projectId: Long, team: Team?, prompt: String, onNotStarted: () -> Unit, onStarted: (String) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
    private val teamOptions: (projectId: Long) -> TeamOptions = { TeamOptions.NONE },
) {
    private val tools = ReviewToolState()

    /** The toolbar's sticky default tool. */
    var tool: ReviewTool
        get() = tools.tool
        set(value) { tools.tool = value }
    val oneShotTool: ReviewTool? get() = tools.oneShotTool
    internal val draft: PolygonDraft get() = tools.draft
    var selectedMark: Int?
        get() = tools.selectedMark
        set(value) { tools.selectedMark = value }
    var sendTarget by mutableStateOf<SendTarget?>(null)
        private set

    private val reviewsByProject = mutableStateMapOf<Long, List<Review>>()
    private val selection = mutableStateMapOf<Long, Pair<Long, Int>>()
    private val imagesByReview = mutableStateMapOf<Long, List<ReviewImage>>()
    private val fullImages = BitmapCache(scope, FULL_CACHE_SIZE) { decode(service.imagePath(it)) }
    private val thumbnails = BitmapCache(scope, THUMBNAIL_CACHE_SIZE) { decodeThumbnail(service.imagePath(it)) }
    private val loadingImages = ConcurrentHashMap.newKeySet<Long>()
    private val markStore = ReviewMarkStore(service, scope, reportError)
    private val watchedProjects = ConcurrentHashMap.newKeySet<Long>()
    private val cacheLock = Any()
    @Volatile private var retainEpoch = 0
    private var unsubscribe: Runnable? = null
    @Volatile private var finalFlush: Job? = null
    private val reload = Refresher(scope) {
        watchedProjects.toList().forEach { projectId ->
            runGuarded(reportError) {
                val epoch = retainEpoch
                val fresh = io { service.list(projectId) }.sortedWith(compareByDescending<Review> { it.createdAt }.thenByDescending { it.id })
                val toRefresh = synchronized(cacheLock) {
                    if (projectId !in watchedProjects || epoch != retainEpoch) return@runGuarded
                    val gone = reviewsByProject[projectId].orEmpty().map { it.id } - fresh.map { it.id }.toSet()
                    reviewsByProject[projectId] = fresh
                    gone.forEach(::forgetReview)
                    fresh.map { it.id }.filter { it in imagesByReview }
                }
                toRefresh.forEach { id -> refreshImages(id, epoch) }
            }
        }
    }

    fun start() {
        unsubscribe = service.subscribe(Runnable { reload.request() })
        reload.request()
    }

    /** Writes pending marks on an IO scope that outlives [scope], so the UI thread never waits for it. */
    fun dispose() {
        unsubscribe?.run()
        unsubscribe = null
        reload.close()
        finalFlush = CoroutineScope(Dispatchers.IO).launch { runGuarded(reportError) { markStore.flush() } }
    }

    /** For the shutdown thread only: waits (bounded) for the flush started by [dispose], so the database is still open for it. */
    fun awaitFinalFlush() {
        val job = finalFlush ?: return
        runBlocking { withTimeoutOrNull(FINAL_FLUSH_TIMEOUT_MILLIS) { job.join() } }
    }

    /** Drops the reviews, images and decoded pictures of every project that is not in [existing]. */
    fun retain(existing: Collection<Long>) {
        synchronized(cacheLock) {
            val gone = watchedProjects + reviewsByProject.keys - existing.toSet()
            // Only a real removal makes in-flight results stale; a list reload that kept every project must not drop them.
            if (gone.isNotEmpty()) retainEpoch++
            gone.forEach { projectId ->
                watchedProjects.remove(projectId)
                reviewsByProject.remove(projectId)?.forEach { forgetReview(it.id) }
                selection.remove(projectId)
            }
        }
    }

    fun reviews(projectId: Long): List<Review> {
        if (watchedProjects.add(projectId)) reload.request()
        return reviewsByProject[projectId].orEmpty()
    }

    fun pendingCount(projectId: Long): Int = reviews(projectId).count { it.status == ReviewStatus.PENDING }

    fun selectedReview(projectId: Long): Review? {
        val all = reviews(projectId)
        val chosen = selection[projectId]?.first
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull { it.status == ReviewStatus.PENDING } ?: all.firstOrNull()
    }

    fun selectedImageIndex(projectId: Long): Int = selection[projectId]?.takeIf { it.first == selectedReview(projectId)?.id }?.second ?: 0

    val effectiveTool: ReviewTool get() = tools.effectiveTool

    /** The next gesture draws exactly one element of [kind], then the default tool is back. */
    fun arm(kind: ReviewTool) = tools.arm(kind)

    fun clearOneShot() = tools.clearOneShot()

    /** Esc: drops an armed one-shot first, then the selection; false when there was nothing to drop. */
    fun cancel(): Boolean = tools.cancel()

    fun chooseTool(chosen: ReviewTool) = tools.chooseTool(chosen)

    fun select(projectId: Long, reviewId: Long, imageIndex: Int = 0) {
        selection[projectId] = reviewId to imageIndex
        selectedMark = null
        scope.launch { runGuarded(reportError) { markStore.flush() } }
    }

    fun images(reviewId: Long): List<ReviewImage> {
        imagesByReview[reviewId]?.let { return it }
        val epoch = retainEpoch
        if (loadingImages.add(reviewId)) scope.launch {
            try {
                runGuarded(reportError) {
                    val loaded = io { service.images(reviewId) }
                    synchronized(cacheLock) { if (epoch == retainEpoch) imagesByReview[reviewId] = loaded }
                }
            } finally {
                loadingImages.remove(reviewId)
            }
        }
        return emptyList()
    }

    /** Full-size picture for the viewer; null while decoding or when it cannot be shown (see [cannotShow]). */
    fun bitmap(image: ReviewImage): ImageBitmap? = fullImages.get(image)

    fun thumbnail(image: ReviewImage): ImageBitmap? = thumbnails.get(image)

    fun cannotShow(image: ReviewImage): Boolean = fullImages.failed(image.id)

    /** A screenshot added to an already shown review must appear, so loaded image lists are read again. */
    private suspend fun refreshImages(reviewId: Long, epoch: Int) {
        runGuarded(reportError) {
            val fresh = io { service.images(reviewId) }
            synchronized(cacheLock) { if (epoch == retainEpoch) imagesByReview[reviewId] = fresh }
        }
    }

    private fun forgetReview(reviewId: Long) {
        imagesByReview.remove(reviewId)?.forEach { image ->
            fullImages.drop(image.id)
            thumbnails.drop(image.id)
            markStore.forget(image.id)
        }
    }

    fun marks(imageId: Long): SnapshotStateList<MarkDraft> = markStore.marks(imageId)

    fun addMark(imageId: Long, mark: MarkDraft) {
        val list = marks(imageId)
        list.add(mark)
        tools.clearOneShot()
        selectedMark = list.lastIndex
        markStore.persist(imageId)
    }

    /** Closes the polygon draft into a mark of [imageId]; false (draft kept) when it has too few points or no area. */
    internal fun closeDraft(imageId: Long, imageW: Float, imageH: Float): Boolean {
        val mark = tools.draft.close(imageW, imageH) ?: return false
        addMark(imageId, mark)
        return true
    }

    fun setComment(imageId: Long, index: Int, text: String) = markStore.setComment(imageId, index, text)

    /** Live edit of a mark's geometry; the comment stays and saving is debounced like every mark change. */
    fun updateMark(imageId: Long, index: Int, edited: MarkDraft) = markStore.updateMark(imageId, index, edited)

    fun deleteMark(imageId: Long, index: Int) {
        val list = marks(imageId)
        if (index !in list.indices) return
        list.removeAt(index)
        selectedMark = null
        markStore.persist(imageId)
    }

    fun send(review: Review) {
        scope.launch {
            runGuarded(reportError) {
                val bound = review.astrolabeSessionId
                if (bound == null) offerTargets(review) else deliver(review, bound)
            }
        }
    }

    fun sendTo(review: Review, astrolabeSessionId: String?, team: Team? = null) {
        scope.launch {
            runGuarded(reportError) {
                sendTarget = null
                deliver(review, astrolabeSessionId, team)
            }
        }
    }

    fun dismissSendTarget() {
        sendTarget = null
    }

    private fun isLive(astrolabeSessionId: String): Boolean = live().find(astrolabeSessionId) != null

    private fun candidates(projectId: Long): List<SessionChoice> =
        live().of(projectId).map { SessionChoice(it.astrolabeSessionId, it.title?.takeIf(String::isNotBlank) ?: it.astrolabeSessionId) }

    private fun offerTargets(review: Review) {
        sendTarget = SendTarget(review, candidates(review.projectId), teamOptions(review.projectId))
    }

    /** Flushes marks, then checks the target is alive before the review is marked sent; a dead target reopens the chooser with fresh candidates. */
    private suspend fun deliver(review: Review, astrolabeSessionId: String?, team: Team? = null) {
        markStore.flush()
        if (astrolabeSessionId != null && !isLive(astrolabeSessionId)) {
            offerTargets(review)
            return
        }
        val feedback = io { service.prepareFeedback(review.id) }
        if (astrolabeSessionId != null) enqueue(astrolabeSessionId, feedback.prompt)
        else startSession(review.projectId, team, feedback.prompt, { reportError("$START_FAILED. Feedback is in ${feedback.markdown}") }, { })
    }

    fun approve(review: Review) {
        scope.launch { runGuarded(reportError) { io { service.approve(review.id) } } }
    }

    fun delete(review: Review) {
        scope.launch { runGuarded(reportError) { io { service.delete(review.id) } } }
    }
}
