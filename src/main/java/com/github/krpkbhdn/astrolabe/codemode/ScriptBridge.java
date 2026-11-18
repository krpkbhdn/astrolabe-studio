package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.codemode.catalog.ToolSearch;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClient;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClientException;
import com.github.krpkbhdn.astrolabe.codemode.client.McpServerErrorException;
import com.github.krpkbhdn.astrolabe.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.astrolabe.codemode.policy.CodeModeAudit.Status;
import com.github.krpkbhdn.astrolabe.codemode.policy.Decision;
import com.github.krpkbhdn.astrolabe.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What a running script of one session reaches: every remote tool call is checked by the gate and audited (never with arguments or results). */
final class ScriptBridge implements HostBridge {

    static final String CANCELLED = "The script was cancelled";
    private static final Logger log = LoggerFactory.getLogger(ScriptBridge.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SUGGESTIONS = 5;
    private static final int MAX_LABEL = 100;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private final SessionContext session;
    private final ServerConnections connections;
    private final ToolCallGate gate;
    private final CodeModeAudit audit;
    private final SessionImages images;
    private final AtomicBoolean cancelled;
    private final ApprovalWait approvals;
    private final Duration callTimeout;

    ScriptBridge(SessionContext session, ServerConnections connections, ToolCallGate gate, CodeModeAudit audit, SessionImages images,
            AtomicBoolean cancelled, ApprovalWait approvals, Duration callTimeout) {
        this.session = session;
        this.connections = connections;
        this.gate = gate;
        this.audit = audit;
        this.images = images;
        this.cancelled = cancelled;
        this.approvals = approvals;
        this.callTimeout = callTimeout;
    }

    @Override
    public String serverIndexJson() {
        return connections.namesIndexJson(session);
    }

    @Override
    public String callTool(String serverName, String tool, String argsJson) {
        if (cancelled.get()) {
            throw new IllegalStateException(CANCELLED);
        }
        var arguments = arguments(argsJson);
        var server = connections.resolve(session, serverName);
        var client = connections.client(session, server);
        var remote = connections.catalog(session).byServer(server).stream().filter(t -> t.name().equals(tool)).findFirst()
                .orElseThrow(() -> unknownTool(server, tool));
        var decision = decide(remote, connections.definitionOf(session, server), arguments);
        if (cancelled.get()) {
            record(server, tool, Decision.denied(CANCELLED), Status.ERROR, 0);
            throw new IllegalStateException(CANCELLED);
        }
        if (!decision.allowed()) {
            record(server, tool, decision, Status.ERROR, 0);
            throw new IllegalStateException(decision.reason());
        }
        var started = System.nanoTime();
        try {
            var value = ToolResults.toScriptValue(callWithTimeout(client, server, tool, arguments), images);
            record(server, tool, decision, Status.OK, started);
            return value;
        } catch (IllegalStateException e) {
            record(server, tool, decision, Status.ERROR, started);
            throw e;
        } catch (RuntimeException e) {
            record(server, tool, decision, Status.ERROR, started);
            throw new IllegalStateException("The call to " + ServerNames.shown(server + "." + tool, MAX_LABEL) + " failed: " + e.getMessage());
        }
    }

    private IllegalArgumentException unknownTool(String server, String tool) {
        var tools = connections.catalog(session).byServer(server);
        var close = ToolSearch.rank(tools, tool, SUGGESTIONS).stream().map(RemoteTool::name).toList();
        var names = close.isEmpty() ? tools.stream().limit(SUGGESTIONS).map(RemoteTool::name).toList() : close;
        return new IllegalArgumentException("Unknown tool '" + ServerNames.shown(tool) + "' on server '" + ServerNames.shown(server) + "'."
                + (names.isEmpty() ? "" : " Did you mean: " + ServerNames.listed(names) + ".") + " Use search_tools.");
    }

    private Decision decide(RemoteTool remote, McpServerDefinition definition, JsonNode arguments) {
        if (cancelled.get()) {
            return Decision.denied(CANCELLED);
        }
        approvals.begin(ServerNames.shown(remote.server() + "." + remote.name(), MAX_LABEL));
        try {
            return gate.decide(remote, definition, arguments, session);
        } finally {
            approvals.end();
        }
    }

    /** A failed client is reset so the next use reconnects; a healthy server's error answer keeps it. Past the limit a stuck call is abandoned and its client closed. */
    private JsonNode callWithTimeout(McpClient client, String server, String tool, JsonNode arguments) {
        if (cancelled.get()) {
            throw new IllegalStateException(CANCELLED);
        }
        var call = CALLS.submit(() -> client.callTool(tool, arguments));
        try {
            return call.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            connections.reset(session, server);
            throw new IllegalStateException("The call to " + ServerNames.shown(server + "." + tool, MAX_LABEL) + " timed out after " + callTimeout.toSeconds()
                    + " s (astrolabe.code-mode.tool-call-timeout); the connection to the server was reset, raise that property in astrolabe's configuration");
        } catch (InterruptedException e) {
            call.cancel(true);
            connections.reset(session, server);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(CANCELLED);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof McpClientException && !(e.getCause() instanceof McpServerErrorException)) {
                connections.reset(session, server);
            }
            throw e.getCause() instanceof RuntimeException failure ? failure : new IllegalStateException(e.getCause());
        }
    }

    @Override
    public void log(String line) {
        // console output reaches Claude through the sandbox result; nothing else listens
    }

    private static JsonNode arguments(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            var node = JSON.readTree(argsJson);
            if (node.isObject()) {
                return node;
            }
        } catch (RuntimeException e) {
            log.debug("Script passed unparsable tool arguments");
        }
        throw new IllegalArgumentException("Tool arguments must be a JSON object");
    }

    /** @param started System.nanoTime() of the call start, or 0 for a call that never ran */
    private void record(String server, String tool, Decision decision, Status status, long started) {
        try {
            audit.record(session, server, tool, decision.kind(), status, started == 0 ? 0 : (System.nanoTime() - started) / NANOS_PER_MILLI);
        } catch (RuntimeException e) {
            log.warn("Code Mode audit failed: {}", e.getClass().getSimpleName());
        }
    }
}
