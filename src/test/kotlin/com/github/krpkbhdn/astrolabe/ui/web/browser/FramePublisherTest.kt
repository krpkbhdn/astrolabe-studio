package com.github.krpkbhdn.astrolabe.ui.web.browser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class FramePublisherTest {
    private val flushes = mutableListOf<Runnable>()
    private val scheduler = object : ScheduledThreadPoolExecutor(1) {
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            flushes += command
            return super.schedule({}, 1, TimeUnit.DAYS)
        }
    }
    private val published = mutableListOf<BrowserFrame>()
    private var clock = 0L
    private val publisher = FramePublisher(FrameBuffer(), { synchronized(published) { published += it } }, scheduler, INTERVAL, { clock })

    @AfterEach
    fun stop() {
        scheduler.shutdownNow()
    }

    private fun paint(blue: Int) = publisher.onPaint(ByteBuffer.wrap(byteArrayOf(blue.toByte(), 0, 0, -1)), 1, 1)

    private fun runScheduledFlushes() {
        flushes.toList().also { flushes.clear() }.forEach { it.run() }
    }

    @Test
    fun the_first_paint_goes_out_at_once() {
        paint(1)

        assertThat(published).hasSize(1)
    }

    @Test
    fun paints_inside_the_interval_are_coalesced_and_the_last_one_still_goes_out() {
        paint(1)
        clock += 1
        repeat(20) { paint(it + 10) }
        assertThat(published).hasSize(1)

        clock += INTERVAL
        runScheduledFlushes()

        assertThat(published).hasSize(2)
        val pixel = IntArray(1).also { published.last().bitmap.readPixels(it) }[0]
        assertThat(pixel and 0xFF).isEqualTo(29)
    }

    @Test
    fun nothing_is_published_while_hidden() {
        publisher.hidden = true

        paint(1)

        assertThat(published).isEmpty()
    }

    private companion object {
        const val INTERVAL = 50_000_000L
    }
}
