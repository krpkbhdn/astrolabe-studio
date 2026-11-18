package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.codemode.ServerCandidates.Candidate;
import com.github.krpkbhdn.astrolabe.codemode.SessionServers.Open;
import com.github.krpkbhdn.astrolabe.codemode.client.McpAuthRequiredException;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClient;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClientException;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClients;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts a candidate's client and records its tools or why it could not be used; never holds the session lock while connecting. */
final class ServerConnector {

    private static final Logger log = LoggerFactory.getLogger(ServerConnector.class);

    private final McpClients clients;
    private final Clock clock;

    ServerConnector(McpClients clients, Clock clock) {
        this.clients = clients;
        this.clock = clock;
    }

    McpClient connect(SessionServers pool, Candidate candidate) {
        synchronized (pool) {
            var existing = reusable(pool, candidate);
            if (existing != null) {
                return existing;
            }
        }
        var client = clients.create(candidate.definition());
        List<RemoteTool> tools;
        try {
            client.initialize();
            tools = client.listTools();
        } catch (McpAuthRequiredException e) {
            closeQuietly(client);
            synchronized (pool) {
                pool.signInNeeded.put(candidate.name(), candidate.definition());
                pool.failures.put(candidate.name(), ServerConnections.NEEDS_SIGN_IN);
            }
            throw new IllegalStateException(candidate.name() + ": " + ServerConnections.NEEDS_SIGN_IN);
        } catch (RuntimeException e) {
            closeQuietly(client);
            var reason = "could not connect" + (e instanceof McpClientException ? ": " + e.getMessage() : "");
            synchronized (pool) {
                pool.failures.put(candidate.name(), reason);
            }
            throw new IllegalStateException(candidate.name() + ": " + reason);
        }
        synchronized (pool) {
            var winner = pool.open.get(candidate.name());
            if (winner != null || pool.closed) {
                closeQuietly(client);
                return reusable(pool, candidate);
            }
            pool.catalog.put(candidate.name(), tools.stream().map(tool -> tool.onServer(candidate.name())).toList());
            pool.failures.remove(candidate.name());
            pool.open.put(candidate.name(), new Open(client, candidate.definition(), candidate.stored(), clock.instant()));
            return client;
        }
    }

    /** The open client, null when there is none; the pool lock must be held. */
    private McpClient reusable(SessionServers pool, Candidate candidate) {
        if (pool.closed) {
            throw new IllegalStateException(ServerConnections.SESSION_ENDED);
        }
        var existing = pool.open.get(candidate.name());
        if (existing != null) {
            existing.lastUsed = clock.instant();
            existing.verifiedAt = existing.lastUsed;
            return existing.client;
        }
        var failed = pool.signInNeeded.get(candidate.name());
        if (failed != null && !failed.equals(candidate.definition())) {
            pool.signInNeeded.remove(candidate.name());
            pool.failures.remove(candidate.name());
        } else if (failed != null) {
            throw new IllegalStateException(candidate.name() + ": " + ServerConnections.NEEDS_SIGN_IN);
        }
        return null;
    }

    static void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.debug("Closing a Code Mode client failed: {}", e.getClass().getSimpleName());
        }
    }
}
