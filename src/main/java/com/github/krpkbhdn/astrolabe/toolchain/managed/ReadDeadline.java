package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closes a stream when the time is up, which unblocks a read stalled on a silent server; a plain elapsed-time check never runs then. */
final class ReadDeadline implements AutoCloseable {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "toolchain-read-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private static final long CANCEL_POLL_MILLIS = 100;

    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> timer;
    private final ScheduledFuture<?> cancelWatch;
    private final Duration limit;
    private final String what;

    /** Also closes the stream as soon as the creating thread is interrupted, because a blocked read does not see an interrupt. */
    ReadDeadline(Closeable stream, Duration limit, String what) {
        this.limit = limit;
        this.what = what;
        this.timer = TIMER.schedule(() -> {
            expired.set(true);
            closeQuietly(stream);
        }, limit.toNanos(), TimeUnit.NANOSECONDS);
        var owner = Thread.currentThread();
        this.cancelWatch = TIMER.scheduleWithFixedDelay(() -> {
            if (owner.isInterrupted()) {
                closeQuietly(stream);
            }
        }, CANCEL_POLL_MILLIS, CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // the read fails by itself
        }
    }

    /** Throws the timeout when the deadline fired; otherwise returns so the caller rethrows its own failure. */
    void rethrowIfExpired(IOException cause) throws IOException {
        if (expired.get()) {
            throw new IOException(what + " timed out after " + limit, cause);
        }
    }

    @Override
    public void close() {
        timer.cancel(false);
        cancelWatch.cancel(false);
    }
}
