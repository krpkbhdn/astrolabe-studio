package com.github.krpkbhdn.astrolabe.codemode.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The cancel action of each running request by session and JSON-RPC id, so one session can never cancel another's request; bounded, over the limit a request is just not cancellable. */
final class InFlightRequests {

    static final int MAX_TRACKED = 256;

    private final Map<String, Runnable> cancels = new ConcurrentHashMap<>();

    /** @return removes the entry when run */
    Runnable begin(String sessionId, String requestId, Runnable cancel) {
        var key = sessionId + '\n' + requestId;
        if (cancels.size() >= MAX_TRACKED || cancels.putIfAbsent(key, cancel) != null) {
            return () -> { };
        }
        return () -> cancels.remove(key, cancel);
    }

    /** Unknown or finished ids are ignored; the action runs at most once. */
    void cancel(String sessionId, String requestId) {
        var cancel = cancels.remove(sessionId + '\n' + requestId);
        if (cancel != null) {
            cancel.run();
        }
    }

    int size() {
        return cancels.size();
    }
}
