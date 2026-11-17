package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.codemode.ServerCandidates.Candidate;
import com.github.krpkbhdn.astrolabe.codemode.catalog.ToolCatalog;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClient;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The state of one session's servers (open clients, catalog, failures, pinned aliases); every access holds the instance's monitor. */
final class SessionServers {

    static final class Open {
        final McpClient client;
        final McpServerDefinition definition;
        final String stored;
        Instant lastUsed;
        Instant verifiedAt;

        Open(McpClient client, McpServerDefinition definition, String stored, Instant now) {
            this.client = client;
            this.definition = definition;
            this.stored = stored;
            this.lastUsed = now;
            this.verifiedAt = now;
        }
    }

    final String sessionId;
    final ToolCatalog catalog;
    final Map<String, Open> open = new HashMap<>();
    final Map<String, String> failures = new HashMap<>();
    final Set<String> signInNeeded = new HashSet<>();
    final Map<String, String> aliases = new HashMap<>();
    List<Candidate> lastFound = List.of();
    Instant scannedAt = Instant.MIN;
    boolean closed;

    SessionServers(String sessionId, ToolCatalog catalog) {
        this.sessionId = sessionId;
        this.catalog = catalog;
    }

    ServerStatus statusOf(Candidate candidate) {
        if (candidate.problem() != null) {
            return new ServerStatus(candidate.name(), candidate.stored(), candidate.scope(), candidate.transport(), false, Optional.of(candidate.problem()));
        }
        return new ServerStatus(candidate.name(), candidate.stored(), candidate.scope(), candidate.transport(), open.containsKey(candidate.name()),
                Optional.ofNullable(failures.get(candidate.name())));
    }
}
