package com.github.krpkbhdn.astrolabe.codemode.policy;

import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The script running for each session (at most one), so the UI can offer to stop it. Every script carries its deadline: a newer script
 * replaces one that is {@code OVERDUE_GRACE} past it (the older one is cancelled), so a session never stays busy for good.
 */
@Component
public class ExecutionRegistry {

    static final Duration OVERDUE_GRACE = Duration.ofSeconds(5);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private record Entry(Runnable cancel, long startedNanos, LongSupplier deadlineNanos, Supplier<Optional<String>> waitingForApproval) {}

    private final Map<String, Entry> running = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Code Mode execution");

    /**
     * @param deadlineNanos {@code System.nanoTime()} by which the script should end; may move later while it waits for the user
     * @throws IllegalStateException when the session already runs a script still within its deadline
     */
    public Handle register(String sessionId, LongSupplier deadlineNanos, Runnable cancel) {
        return register(sessionId, deadlineNanos, cancel, Optional::empty);
    }

    /** @param waitingForApproval the {@code server.tool} whose user question the script waits on, if any; a new script then gets a message naming it */
    public Handle register(String sessionId, LongSupplier deadlineNanos, Runnable cancel, Supplier<Optional<String>> waitingForApproval) {
        var entry = new Entry(cancel, System.nanoTime(), deadlineNanos, waitingForApproval);
        while (true) {
            var previous = running.putIfAbsent(sessionId, entry);
            if (previous == null) {
                break;
            }
            var now = System.nanoTime();
            if (now - previous.deadlineNanos().getAsLong() > OVERDUE_GRACE.toNanos()) {
                if (running.replace(sessionId, previous, entry)) {
                    previous.cancel().run();
                    break;
                }
            } else {
                throw new IllegalStateException(busyMessage(previous, now));
            }
        }
        listeners.fire();
        return () -> {
            if (running.remove(sessionId, entry)) {
                listeners.fire();
            }
        };
    }

    private static String busyMessage(Entry previous, long now) {
        var approval = previous.waitingForApproval().get();
        if (approval.isPresent()) {
            return "A script is waiting for your approval of " + approval.get() + " in astrolabe: answer or deny the dialog, then retry";
        }
        var remaining = Math.max(0, previous.deadlineNanos().getAsLong() - now) / NANOS_PER_SECOND;
        return "A script is already running in this session (started " + (now - previous.startedNanos()) / NANOS_PER_SECOND + " s ago, limit "
                + (previous.deadlineNanos().getAsLong() - previous.startedNanos()) / NANOS_PER_SECOND + " s, " + remaining
                + " s left). Wait for it or stop it with the \"Stop script\" chip";
    }

    /** @return false when no script runs for the session */
    public boolean cancel(String sessionId) {
        var entry = running.get(sessionId);
        if (entry == null) {
            return false;
        }
        entry.cancel().run();
        return true;
    }

    public boolean isRunning(String sessionId) {
        return running.containsKey(sessionId);
    }

    public Set<String> runningSessionIds() {
        return Set.copyOf(running.keySet());
    }

    /** @return unsubscribe handle; callback fires (any thread) when a script starts or ends */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
