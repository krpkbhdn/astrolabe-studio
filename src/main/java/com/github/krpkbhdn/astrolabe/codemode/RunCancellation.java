package com.github.krpkbhdn.astrolabe.codemode;

/**
 * The cancel of one execute request: it can only stop the run that request starts, even when it arrives before that run is registered
 * (it then stops it at once) or after it ended (it does nothing). The endpoint hands it to the tool through the request's thread.
 */
public final class RunCancellation {

    private static final ThreadLocal<RunCancellation> CURRENT = new ThreadLocal<>();

    private boolean cancelled;
    private Runnable stop;

    /** The cancellation of the request running on this thread, a fresh one when there is none. */
    public static RunCancellation current() {
        var current = CURRENT.get();
        return current != null ? current : new RunCancellation();
    }

    /** @return detaches it again when run */
    public Runnable attachToThread() {
        CURRENT.set(this);
        return CURRENT::remove;
    }

    public void cancel() {
        Runnable toRun;
        synchronized (this) {
            cancelled = true;
            toRun = stop;
        }
        if (toRun != null) {
            toRun.run();
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Called once the run is registered; stops it right away when the request was cancelled before. */
    void bind(Runnable stopRun) {
        boolean alreadyCancelled;
        synchronized (this) {
            stop = stopRun;
            alreadyCancelled = cancelled;
        }
        if (alreadyCancelled) {
            stopRun.run();
        }
    }
}
