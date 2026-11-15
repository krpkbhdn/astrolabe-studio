package com.github.krpkbhdn.ccmanager.claude.proposal;

import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The trust level and folder each live session was started with; a session never listed (Assistant, group) asks. */
@Component
public class SessionTrust {

    /** [directory] is where claude runs, the project folder or one of its worktrees; file tools are auto-allowed only inside it. */
    public record Grant(TrustLevel level, Path directory) {}

    private static final Grant ASK = new Grant(TrustLevel.ASK, null);

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public void set(String ccmSessionId, TrustLevel level, Path directory) {
        grants.put(ccmSessionId, new Grant(level, directory));
    }

    public void forget(String ccmSessionId) {
        grants.remove(ccmSessionId);
    }

    public Grant of(String ccmSessionId) {
        return grants.getOrDefault(ccmSessionId, ASK);
    }
}
