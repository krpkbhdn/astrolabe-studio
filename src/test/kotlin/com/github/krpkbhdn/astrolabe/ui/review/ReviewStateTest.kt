package com.github.krpkbhdn.astrolabe.ui.review

import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.review.MarkDraft
import com.github.krpkbhdn.astrolabe.review.MarkKind
import com.github.krpkbhdn.astrolabe.review.Review
import com.github.krpkbhdn.astrolabe.review.ReviewFeedback
import com.github.krpkbhdn.astrolabe.review.ReviewImage
import com.github.krpkbhdn.astrolabe.review.ReviewService
import com.github.krpkbhdn.astrolabe.review.ReviewStatus
import com.github.krpkbhdn.astrolabe.ui.await
import com.github.krpkbhdn.astrolabe.ui.state.LiveSessions
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.github.krpkbhdn.astrolabe.team.Team
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ReviewStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = mock(ReviewService::class.java)
    private val errors = CopyOnWriteArrayList<String>()
    private val enqueued = CopyOnWriteArrayList<Pair<String, String>>()
    private val started = CopyOnWriteArrayList<String>()
    @Volatile private var liveSessions = LiveSessions()
    private val state = ReviewState(service, { liveSessions }, { id, text -> enqueued += id to text }, { _, _, prompt, _, _ -> started += prompt }, scope, { errors += it })
    private val mark = MarkDraft(MarkKind.RECTANGLE, 0.1, 0.2, 0.3, 0.4, "")

    @AfterEach
    fun stop() = scope.cancel()

    private fun review(id: Long, status: ReviewStatus, session: String? = null) =
        Review(id, 1, session, "r$id", "", status, Instant.ofEpochSecond(id), null)

    private fun snapshot(id: String) = mock(SessionSnapshot::class.java).also {
        `when`(it.astrolabeSessionId).thenReturn(id)
        `when`(it.projectId).thenReturn(1L)
        `when`(it.title).thenReturn("Session $id")
    }

    private fun feedback() = `when`(service.prepareFeedback(5)).thenReturn(ReviewFeedback(Path.of("review.md"), "Read review.md"))

    @Test
    fun `a screenshot added to a shown review appears after the next change notification`() {
        var changed = Runnable { }
        `when`(service.subscribe(org.mockito.ArgumentMatchers.any())).thenAnswer { changed = it.getArgument(0); Runnable { } }
        `when`(service.list(1)).thenReturn(listOf(review(1, ReviewStatus.PENDING)))
        val first = ReviewImage(10, 1, 0, "a", "a.png", 1, 1)
        val second = ReviewImage(11, 1, 1, "b", "b.png", 1, 1)
        val current = java.util.concurrent.atomic.AtomicReference(listOf(first))
        `when`(service.images(1)).thenAnswer { current.get() }
        state.start()
        await { state.reviews(1).isNotEmpty() }
        await { state.images(1).size == 1 }

        current.set(listOf(first, second))
        changed.run()

        await { state.images(1).size == 2 }
    }

    @Test
    fun `a project that is gone loses its cached reviews and images`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val hold = AtomicBoolean()
        // Reading through the state re-watches the project and reloads it; once held, only retain's effect is seen.
        // Loads that started before the hold carry the old epoch and are discarded, later ones park on the gate.
        `when`(service.list(1)).thenAnswer { if (hold.get()) gate.await(); listOf(review(1, ReviewStatus.PENDING)) }
        `when`(service.images(1)).thenAnswer { if (hold.get()) gate.await(); listOf(ReviewImage(10, 1, 0, "a", "a.png", 1, 1)) }
        state.start()
        await { state.reviews(1).isNotEmpty() }
        await { state.images(1).isNotEmpty() }
        hold.set(true)
        try {
            state.retain(listOf(2L))

            assertThat(state.images(1)).isEmpty()
            assertThat(state.selectedReview(1)).isNull()
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `retain for a project that stays does not discard its load in flight`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        `when`(service.list(1)).thenAnswer { gate.await(); listOf(review(1, ReviewStatus.PENDING)) }
        `when`(service.images(1)).thenAnswer { gate.await(); listOf(ReviewImage(10, 1, 0, "a", "a.png", 1, 1)) }
        state.start()
        state.reviews(1)
        state.images(1)
        state.retain(listOf(1L))
        gate.countDown()

        await { state.reviews(1).isNotEmpty() }
        await { state.images(1).isNotEmpty() }
    }

    @Test
    fun `the newest pending review is selected by default and counted`() {
        `when`(service.list(1)).thenReturn(listOf(review(1, ReviewStatus.PENDING), review(2, ReviewStatus.APPROVED), review(3, ReviewStatus.PENDING)))
        state.start()
        await { state.reviews(1).size == 3 }

        assertThat(state.reviews(1).map { it.id }).containsExactly(3L, 2L, 1L)
        assertThat(state.selectedReview(1)!!.id).isEqualTo(3)
        assertThat(state.pendingCount(1)).isEqualTo(2)
        state.select(1, 1, 2)
        assertThat(state.selectedReview(1)!!.id).isEqualTo(1)
        assertThat(state.selectedImageIndex(1)).isEqualTo(2)
    }

    @Test
    fun `added, commented and deleted marks are saved debounced with the exact list`() {
        state.addMark(7, mark)
        state.addMark(7, MarkDraft(MarkKind.PIN, 0.5, 0.5, 0.0, 0.0, ""))
        state.setComment(7, 0, "too big")
        state.deleteMark(7, 1)
        val expected = MarkDraft(MarkKind.RECTANGLE, 0.1, 0.2, 0.3, 0.4, "too big")

        verify(service, timeout(3000)).saveMarks(7, listOf(expected))
        assertThat(state.marks(7)).containsExactly(expected)
    }

    @Test
    fun `an armed one-shot tool wins over the default and clears once a mark is added or on cancel`() {
        assertThat(state.tool).isEqualTo(ReviewTool.POINTER)
        state.arm(ReviewTool.RECTANGLE)
        assertThat(state.effectiveTool).isEqualTo(ReviewTool.RECTANGLE)
        state.addMark(7, mark)
        assertThat(state.oneShotTool).isNull()
        assertThat(state.effectiveTool).isEqualTo(ReviewTool.POINTER)

        state.arm(ReviewTool.PIN)
        state.cancel()
        assertThat(state.oneShotTool).isNull()
        assertThat(state.selectedMark).isEqualTo(0)
        assertThat(state.cancel()).isTrue()
        assertThat(state.selectedMark).isNull()
        assertThat(state.cancel()).isFalse()
    }

    @Test
    fun `updating a mark keeps its comment`() {
        state.addMark(7, MarkDraft(MarkKind.RECTANGLE, 0.1, 0.2, 0.3, 0.4, "keep"))
        state.updateMark(7, 0, MarkDraft(MarkKind.RECTANGLE, 0.5, 0.5, 0.1, 0.1, ""))

        assertThat(state.marks(7)).containsExactly(MarkDraft(MarkKind.RECTANGLE, 0.5, 0.5, 0.1, 0.1, "keep"))
    }

    @Test
    fun `send to a live bound session enqueues the one-line prompt`() {
        feedback()
        liveSessions = LiveSessions(sessions = listOf(snapshot("s1")))

        state.send(review(5, ReviewStatus.PENDING, "s1"))

        await { enqueued.isNotEmpty() }
        assertThat(enqueued).containsExactly("s1" to "Read review.md")
        assertThat(state.sendTarget).isNull()
    }

    @Test
    fun `send with a dead session offers the live sessions of the project`() {
        liveSessions = LiveSessions(sessions = listOf(snapshot("s2")))

        state.send(review(5, ReviewStatus.PENDING, "gone"))

        await { state.sendTarget != null }
        assertThat(state.sendTarget!!.candidates).containsExactly(SessionChoice("s2", "Session s2"))
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `sending to no session starts a new one`() {
        feedback()
        val review = review(5, ReviewStatus.PENDING)

        state.sendTo(review, null)

        await { started.isNotEmpty() }
        assertThat(started).containsExactly("Read review.md")
        assertThat(state.sendTarget).isNull()
    }

    @Test
    fun `approve settles the review without a prompt`() {
        state.approve(review(5, ReviewStatus.PENDING))

        verify(service, timeout(3000)).approve(5)
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `sending right after a mark edit saves the latest marks before the feedback is prepared`() {
        feedback()
        liveSessions = LiveSessions(sessions = listOf(snapshot("s1")))
        state.addMark(7, mark)
        state.setComment(7, 0, "latest")

        state.send(review(5, ReviewStatus.PENDING, "s1"))

        await { enqueued.isNotEmpty() }
        val order = inOrder(service)
        order.verify(service).saveMarks(7, listOf(MarkDraft(MarkKind.RECTANGLE, 0.1, 0.2, 0.3, 0.4, "latest")))
        order.verify(service).prepareFeedback(5)
    }

    @Test
    fun `dispose saves pending marks without waiting for the debounce`() {
        state.addMark(7, mark)

        state.dispose()

        verify(service, timeout(2000)).saveMarks(7, listOf(mark))
    }

    @Test
    fun `awaiting the final flush returns only after the marks are saved`() {
        state.addMark(7, mark)

        state.dispose()
        state.awaitFinalFlush()

        verify(service).saveMarks(7, listOf(mark))
    }

    @Test
    fun `a failed save keeps the marks dirty so the next flush writes them again`() {
        doThrow(RuntimeException("disk full")).doNothing().`when`(service).saveMarks(7, listOf(mark))
        state.addMark(7, mark)

        state.dispose()
        await { errors.isNotEmpty() }
        assertThat(errors).containsExactly("disk full")
        state.dispose()

        verify(service, timeout(2000).times(2)).saveMarks(7, listOf(mark))
    }

    @Test
    fun `marks are not saved while the stored ones could not be read`() {
        val readable = AtomicBoolean(false)
        `when`(service.marks(7)).thenAnswer { if (readable.get()) emptyList<Any>() else throw RuntimeException("db locked") }
        state.addMark(7, mark)

        state.dispose()
        verify(service, timeout(2000).atLeastOnce()).marks(7)
        state.dispose()
        state.awaitFinalFlush()
        verify(service, never()).saveMarks(anyLong(), anyList())

        readable.set(true)
        state.dispose()
        verify(service, timeout(2000)).saveMarks(7, listOf(mark))
    }

    @Test
    fun `a send to a session that ended meanwhile offers the chooser and keeps the review unsent`() {
        liveSessions = LiveSessions(sessions = listOf(snapshot("s2")))

        state.sendTo(review(5, ReviewStatus.PENDING), "gone")

        await { state.sendTarget != null }
        assertThat(state.sendTarget!!.candidates).containsExactly(SessionChoice("s2", "Session s2"))
        verify(service, never()).prepareFeedback(5)
    }

    @Test
    fun `the chooser offers the project teams and a new session starts with the chosen one`() {
        feedback()
        val team = Team(1, "Dev team", emptyList())
        val teams = mutableListOf<Team?>()
        val withTeams = ReviewState(service, { liveSessions }, { _, _ -> }, { _, chosen, _, _, _ -> teams += chosen }, scope, { errors += it }, { TeamOptions(listOf(team), team) })
        val review = review(5, ReviewStatus.PENDING)

        withTeams.send(review)
        await { withTeams.sendTarget != null }
        assertThat(withTeams.sendTarget!!.teams).isEqualTo(TeamOptions(listOf(team), team))
        withTeams.sendTo(review, null, team)

        await { teams.isNotEmpty() }
        assertThat(teams).containsExactly(team)
    }

    @Test
    fun `a new session that does not start reports where the feedback file is`() {
        feedback()
        val failing = ReviewState(service, { liveSessions }, { _, _ -> }, { _, _, _, onNotStarted, _ -> onNotStarted() }, scope, { errors += it })

        failing.sendTo(review(5, ReviewStatus.PENDING), null)

        await { errors.isNotEmpty() }
        assertThat(errors).containsExactly("Could not start a session. Feedback is in review.md")
    }
}
