package com.github.krpkbhdn.ccmanager.toolchain.managed;

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

    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> timer;
    private final Duration limit;
    private final String what;

    ReadDeadline(Closeable stream, Duration limit, String what) {
        this.limit = limit;
        this.what = what;
        this.timer = TIMER.schedule(() -> {
            expired.set(true);
            try {
                stream.close();
            } catch (IOException ignored) {
                // the read fails by itself
            }
        }, limit.toNanos(), TimeUnit.NANOSECONDS);
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
    }
}
