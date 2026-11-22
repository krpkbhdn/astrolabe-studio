package com.github.krpkbhdn.astrolabe.ui.terminal

import com.github.krpkbhdn.astrolabe.ui.await
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** A [TtyConnector] without a process: [feed] plays process output into the emulator, what the terminal writes is collected in [written]. */
internal class ScriptedConnector : TtyConnector {
    private val output = LinkedBlockingQueue<String>()
    private var pending = ""
    val written = CopyOnWriteArrayList<ByteArray>()
    val resizes = CopyOnWriteArrayList<TermSize>()

    @Volatile
    private var closed = false

    private val fed = AtomicInteger()
    private val received = AtomicInteger()

    @Volatile
    private var processed = 0

    fun feed(text: String) {
        fed.incrementAndGet()
        output.put(text)
    }

    fun feedBytes(bytes: ByteArray) = feed(String(bytes, Charsets.UTF_8))

    /** True until the emulator came back for more after every fed chunk, so a chunk taken but not yet parsed still counts. */
    fun hasUnreadOutput(): Boolean = processed != fed.get()

    fun writtenText(): String = written.joinToString("") { String(it, Charsets.ISO_8859_1) }

    /** Waits for the emulator's writer thread to hand over at least as many bytes as [expected] has, then returns everything written. */
    fun awaitWritten(expected: String): String {
        await { writtenText().length >= expected.length }
        return writtenText()
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        while (pending.isEmpty()) {
            processed = received.get()
            if (closed) return -1
            pending = output.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.also { received.incrementAndGet() } ?: ""
        }
        val count = minOf(length, pending.length)
        pending.toCharArray(buf, offset, 0, count)
        pending = pending.substring(count)
        return count
    }

    override fun write(bytes: ByteArray) {
        written.add(bytes)
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun isConnected() = !closed

    override fun resize(termSize: TermSize) {
        resizes.add(termSize)
    }

    override fun waitFor(): Int = 0

    override fun ready() = output.isNotEmpty()

    override fun getName() = "scripted"

    override fun close() {
        closed = true
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

private const val SETTLE_POLL_NANOS = 1_000_000L
private const val SETTLE_QUIET_NANOS = 32_000_000L
private const val SETTLE_LIMIT_NANOS = 5_000_000_000L

/** Waits until the emulator has consumed all fed output and no frame has fired for longer than the emulator's frame delay (16 ms). */
internal fun settle(connector: ScriptedConnector, session: TerminalSession) {
    val deadline = System.nanoTime() + SETTLE_LIMIT_NANOS
    var quietSince = System.nanoTime()
    var lastFrame = session.frame
    while (System.nanoTime() - quietSince < SETTLE_QUIET_NANOS && System.nanoTime() < deadline) {
        LockSupport.parkNanos(SETTLE_POLL_NANOS)
        val frame = session.frame
        if (frame != lastFrame || connector.hasUnreadOutput()) quietSince = System.nanoTime()
        lastFrame = frame
    }
    session.syncView()
}
