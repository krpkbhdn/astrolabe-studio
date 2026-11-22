package com.github.krpkbhdn.astrolabe.ui.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.github.krpkbhdn.astrolabe.ui.await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val WAIT_MILLIS = 60_000L

class TerminalEmulatorTest {
    private class FailingOnceConnector : TtyConnector {
        val reads = AtomicInteger()

        @Volatile
        var closed = false

        override fun read(buf: CharArray, offset: Int, length: Int): Int = when (reads.getAndIncrement()) {
            0 -> throw IllegalStateException("bad chunk")
            1 -> "after".also { it.toCharArray(buf, offset, 0, it.length) }.length
            else -> { closed = true; -1 }
        }

        override fun write(bytes: ByteArray) = Unit
        override fun write(string: String) = Unit
        override fun isConnected() = !closed
        override fun resize(termSize: TermSize) = Unit
        override fun waitFor() = 0
        override fun ready() = true
        override fun getName() = "failing-once"
        override fun close() { closed = true }
    }

    @Test
    fun `an unexpected exception while reading does not end the output`() {
        val connector = FailingOnceConnector()
        val emulator = TerminalEmulator(connector, 40, 5, TextStyle.EMPTY, {})

        emulator.start()

        await { emulator.buffer.getScreenLines().contains("[Session ended") }
        assertThat(emulator.buffer.getScreenLines()).contains("after").contains("[Session ended (exit code 0)]")
        emulator.close()
    }

    private class AlwaysFailingConnector : TtyConnector {
        val reads = AtomicInteger()

        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            throw IllegalStateException("bad chunk")
        }

        override fun write(bytes: ByteArray) = Unit
        override fun write(string: String) = Unit
        override fun isConnected() = true
        override fun resize(termSize: TermSize) = Unit
        override fun waitFor() = 0
        override fun ready() = true
        override fun getName() = "always-failing"
        override fun close() = Unit
    }

    @Test
    fun `repeated parse failures stop the reader instead of spinning`() {
        val connector = AlwaysFailingConnector()
        val emulator = TerminalEmulator(connector, 40, 5, TextStyle.EMPTY, {}, exitWaitMillis = 0)

        emulator.start()

        await { emulator.buffer.getScreenLines().contains("[Output closed]") }
        assertThat(connector.reads.get()).isLessThan(MAX_PENDING_WRITES)
        emulator.close()
    }

    private class BlockedConnector : TtyConnector {
        val release = CountDownLatch(1)
        val written = CopyOnWriteArrayList<List<Byte>>()

        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
            return -1
        }

        override fun write(bytes: ByteArray) {
            release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
            written += bytes.toList()
        }

        override fun write(string: String) = Unit
        override fun isConnected() = true
        override fun resize(termSize: TermSize) = Unit
        override fun waitFor() = 0
        override fun ready() = true
        override fun getName() = "blocked"
        override fun close() = release.countDown()
    }

    @Test
    fun `control bytes still reach a process whose stdin queue is full`() {
        val connector = BlockedConnector()
        val emulator = TerminalEmulator(connector, 40, 5, TextStyle.EMPTY, {})
        repeat(MAX_PENDING_WRITES + 10) { emulator.write(byteArrayOf('x'.code.toByte())) }

        emulator.write(byteArrayOf(0x03))
        emulator.write(byteArrayOf(0x1B))
        connector.release.countDown()

        await { connector.written.size >= MAX_PENDING_WRITES + 2 }
        assertThat(connector.written).contains(listOf(0x03.toByte()), listOf(0x1B.toByte()))
        assertThat(connector.written.size).isEqualTo(MAX_PENDING_WRITES + 2)
        emulator.close()
    }

    @Test
    fun `control bytes are dropped past the hard ceiling`() {
        val connector = BlockedConnector()
        val emulator = TerminalEmulator(connector, 40, 5, TextStyle.EMPTY, {})
        repeat(MAX_PENDING_CONTROL_WRITES + 500) { emulator.write(byteArrayOf(0x0D)) }
        connector.release.countDown()

        await { connector.written.size >= MAX_PENDING_CONTROL_WRITES }
        assertThat(connector.written.size).isEqualTo(MAX_PENDING_CONTROL_WRITES)
        emulator.close()
    }

    @Test
    fun `only single Ctrl+C, Esc and Enter bytes are control writes`() {
        assertThat(isControlWrite(byteArrayOf(0x03))).isTrue()
        assertThat(isControlWrite(byteArrayOf(0x1B))).isTrue()
        assertThat(isControlWrite(byteArrayOf(0x0D))).isTrue()
        assertThat(isControlWrite(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))).isFalse()
        assertThat(isControlWrite(byteArrayOf('x'.code.toByte()))).isFalse()
    }
}
