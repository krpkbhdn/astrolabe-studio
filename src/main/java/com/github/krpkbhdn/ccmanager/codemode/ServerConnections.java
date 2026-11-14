package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.codemode.catalog.ToolCatalog;
import com.github.krpkbhdn.ccmanager.codemode.client.McpClient;
import com.github.krpkbhdn.ccmanager.codemode.ServerCandidates.Candidate;
import com.github.krpkbhdn.ccmanager.codemode.client.McpClients;
import com.github.krpkbhdn.ccmanager.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.workspace.ScheduledSweep;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per session, the lazily connected MCP clients of the project's eligible Code Mode servers (keyed by script alias, fixed at the session's first call) and their tool catalog.
 * An open client is re-checked against the candidates every {@code candidateRecheckTtl}; idle clients and ended sessions are closed, and a server that is dropped or whose definition changed loses its permission grants.
 */
@Component
public class ServerConnections extends ScheduledSweep {

    static final String NEEDS_SIGN_IN = "needs sign-in, which Code Mode cannot do";
    static final String SESSION_ENDED = "The session has ended";
    private static final Logger log = LoggerFactory.getLogger(ServerConnections.class);
    private static final int SWEEPS_PER_IDLE_PERIOD = 2;
    private static final int MAX_CLOSED_SESSIONS = 10_000;

    private final ServerCandidates candidates;
    private final ProjectCodeModeService modes;
    private final ServerConnector connector;
    private final CodeModeProperties properties;
    private final ToolCallGate gate;
    private final Clock clock;
    private final Map<String, SessionServers> pools = new ConcurrentHashMap<>();
    /** shortcut: the oldest ids drop past the cap, so a call of a session ended long ago could get a pool again; its clients are closed by the idle sweep. */
    private final Set<String> closedSessions = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_CLOSED_SESSIONS;
        }
    }));

    @Autowired
    public ServerConnections(ServerCandidates candidates, ProjectCodeModeService modes, McpClients clients, CodeModeProperties properties, ToolCallGate gate) {
        this(candidates, modes, clients, properties, gate, Clock.systemUTC());
    }

    ServerConnections(ServerCandidates candidates, ProjectCodeModeService modes, McpClients clients, CodeModeProperties properties, ToolCallGate gate, Clock clock) {
        super("code-mode-clients", properties.idleClientClose().dividedBy(SWEEPS_PER_IDLE_PERIOD));
        this.candidates = candidates;
        this.modes = modes;
        this.connector = new ServerConnector(clients, clock);
        this.properties = properties;
        this.gate = gate;
        this.clock = clock;
    }

    public List<ServerStatus> statuses(SessionContext session) {
        var pool = pool(session);
        var eligible = candidatesOf(pool, session);
        synchronized (pool) {
            return eligible.stream().map(candidate -> pool.statusOf(candidate)).toList();
        }
    }

    /** Script aliases of the session's eligible servers; nothing is connected. */
    public List<String> aliases(SessionContext session) {
        return ServerAliases.eligible(candidatesOf(pool(session), session));
    }

    /** See {@link ServerAliases#indexJson}; nothing is connected. */
    public String namesIndexJson(SessionContext session) {
        return ServerAliases.indexJson(candidatesOf(pool(session), session));
    }

    /**
     * Resolves against the last scan while it is younger than {@code candidateRecheckTtl}, rescans only for a name it does not know.
     * @return the script alias
     * @throws IllegalStateException telling the available aliases when no server of the session has that name
     */
    public String resolve(SessionContext session, String name) {
        var pool = pool(session);
        Optional<List<Candidate>> recent;
        synchronized (pool) {
            recent = pool.scannedAt.plus(properties.candidateRecheckTtl()).isAfter(clock.instant()) ? Optional.of(pool.lastFound) : Optional.empty();
        }
        return recent.flatMap(found -> ServerAliases.resolve(found, name)).orElseGet(() -> {
            var found = candidatesOf(pool, session);
            return ServerAliases.resolve(found, name).orElseThrow(() -> ServerAliases.unknown(found, name));
        });
    }

    /** Connects every eligible server not connected yet (failures are kept as the server's problem) and returns the session's catalog. */
    public ToolCatalog connectAll(SessionContext session) {
        return connectWhere(session, candidate -> true);
    }

    /** Like {@link #connectAll} for the one server with that script alias, so a search on one server never starts the others. */
    public ToolCatalog connectOnly(SessionContext session, String server) {
        return connectWhere(session, candidate -> candidate.name().equals(server));
    }

    private ToolCatalog connectWhere(SessionContext session, Predicate<Candidate> wanted) {
        var pool = pool(session);
        candidatesOf(pool, session).stream().filter(candidate -> candidate.problem() == null && wanted.test(candidate)).forEach(candidate -> {
            try {
                connector.connect(pool, candidate);
            } catch (IllegalStateException e) {
                log.debug("Code Mode server {} unavailable: {}", candidate.name(), e.getMessage());
            }
        });
        return pool.catalog;
    }

    /** The definition the server's open client was started from, which a permission grant is bound to. */
    public McpServerDefinition definitionOf(SessionContext session, String server) {
        var pool = pool(session);
        synchronized (pool) {
            var open = pool.open.get(server);
            if (open == null) {
                throw new IllegalStateException(server + ": the connection changed, call it again");
            }
            return open.definition;
        }
    }

    /** @throws IllegalStateException with the reason when the server is not a usable Code Mode server of the project */
    public McpClient client(SessionContext session, String server) {
        var pool = pool(session);
        var mode = modes.get(session.projectId());
        if (!mode.enabled()) {
            drop(pool, server);
            throw new IllegalStateException("'" + server + "' is not a Code Mode server of this project");
        }
        synchronized (pool) {
            var open = pool.open.get(server);
            if (open != null && mode.servers().contains(open.stored) && open.verifiedAt.plus(properties.candidateRecheckTtl()).isAfter(clock.instant())) {
                open.lastUsed = clock.instant();
                return open.client;
            }
        }
        var candidate = candidatesOf(pool, session).stream().filter(c -> c.name().equals(server)).findFirst().orElse(null);
        if (candidate == null || candidate.problem() != null) {
            drop(pool, server);
            throw new IllegalStateException(candidate == null ? "'" + server + "' is not a Code Mode server of this project" : server + ": " + candidate.problem());
        }
        refuseChangedDefinition(pool, candidate);
        return connector.connect(pool, candidate);
    }

    /** The session's candidates with the aliases fixed at its first call, so a script's names never change mid-session; servers that are no longer eligible lose their client and catalog entry. */
    private List<Candidate> candidatesOf(SessionServers pool, SessionContext session) {
        Map<String, String> pinned;
        synchronized (pool) {
            pinned = Map.copyOf(pool.aliases);
        }
        var found = candidates.of(session, pinned);
        var live = new HashSet<String>();
        synchronized (pool) {
            pool.lastFound = found;
            pool.scannedAt = clock.instant();
            found.stream().filter(candidate -> candidate.problem() == null).forEach(candidate -> {
                pool.aliases.putIfAbsent(candidate.stored(), candidate.name());
                live.add(candidate.name());
            });
            pool.catalog.all().stream().map(RemoteTool::server).distinct().filter(server -> !live.contains(server)).toList().forEach(server -> drop(pool, server));
        }
        return found;
    }

    /** Closes the server's client and forgets its tools; the pool lock is taken here. */
    private void drop(SessionServers pool, String server) {
        synchronized (pool) {
            var open = pool.open.remove(server);
            if (open != null) {
                ServerConnector.closeQuietly(open.client);
            }
            pool.catalog.remove(server);
        }
        gate.forgetServer(pool.sessionId, server);
    }

    private void refuseChangedDefinition(SessionServers pool, Candidate candidate) {
        synchronized (pool) {
            var open = pool.open.get(candidate.name());
            if (open != null && !open.definition.equals(candidate.definition())) {
                pool.open.remove(candidate.name());
                ServerConnector.closeQuietly(open.client);
                gate.forgetServer(pool.sessionId, candidate.name());
                throw new IllegalStateException(candidate.name() + ": its definition changed, so the connection was closed; call it again to reconnect");
            }
        }
    }

    /** Drops the server's client (closing it) so the next call reconnects; the catalog is kept. */
    public void reset(SessionContext session, String server) {
        var pool = pool(session);
        synchronized (pool) {
            var open = pool.open.remove(server);
            if (open != null) {
                ServerConnector.closeQuietly(open.client);
            }
        }
    }

    public ToolCatalog catalog(SessionContext session) {
        return pool(session).catalog;
    }

    public Set<String> sessionIds() {
        return Set.copyOf(pools.keySet());
    }

    /** Afterwards the session gets no pool again (a late call fails instead of leaking clients). */
    public void closeSession(String sessionId) {
        var removed = new AtomicReference<SessionServers>();
        pools.compute(sessionId, (id, pool) -> {
            closedSessions.add(id);
            removed.set(pool);
            return null;
        });
        var pool = removed.get();
        if (pool != null) {
            synchronized (pool) {
                pool.closed = true;
                pool.open.values().forEach(open -> ServerConnector.closeQuietly(open.client));
                pool.open.clear();
            }
        }
    }

    /** Closes clients idle for {@code idleClientClose}; the catalog is kept, the next call reconnects. */
    @Override
    public void sweep() {
        try {
            var limit = clock.instant().minus(properties.idleClientClose());
            pools.values().forEach(pool -> {
                synchronized (pool) {
                    pool.open.values().removeIf(open -> {
                        boolean idle = open.lastUsed.isBefore(limit);
                        if (idle) {
                            ServerConnector.closeQuietly(open.client);
                        }
                        return idle;
                    });
                }
            });
        } catch (RuntimeException | Error e) {
            log.error("Code Mode client cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private SessionServers pool(SessionContext session) {
        return pools.compute(session.sessionId(), (id, existing) -> {
            if (closedSessions.contains(id)) {
                throw new IllegalStateException(SESSION_ENDED);
            }
            return existing != null ? existing : new SessionServers(id, new ToolCatalog(properties.searchLimit()));
        });
    }
}
