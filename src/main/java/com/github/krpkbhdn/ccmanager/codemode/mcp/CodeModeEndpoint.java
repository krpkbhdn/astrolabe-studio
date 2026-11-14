package com.github.krpkbhdn.ccmanager.codemode.mcp;

import com.github.krpkbhdn.ccmanager.claude.mcp.McpJsonRpcServer;
import com.github.krpkbhdn.ccmanager.claude.session.SessionRegistry;
import com.github.krpkbhdn.ccmanager.codemode.CodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.RunCancellation;
import com.github.krpkbhdn.ccmanager.codemode.ServerConnections;
import com.github.krpkbhdn.ccmanager.codemode.ServerNames;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The MCP server {@code cc-manager-code} of a project session: search the tools of the project's MCP servers and run a script against them. */
@Component
public class CodeModeEndpoint extends McpJsonRpcServer<CodeModeTool> {

    public static final String SERVER_NAME = "cc-manager-code";
    static final String GENERIC_INSTRUCTIONS = "Code Mode: the project's other MCP servers are reached through scripts instead of separate tools. "
            + "Call list_servers, then search_tools to find a tool and its signature, then execute to run JavaScript that calls it.";
    private static final Logger log = LoggerFactory.getLogger(CodeModeEndpoint.class);

    private final SessionRegistry sessions;
    private final ServerConnections connections;
    private final CodeModeService service;
    private final InFlightRequests inFlight = new InFlightRequests();

    public CodeModeEndpoint(List<CodeModeTool> tools, SessionRegistry sessions, ServerConnections connections, CodeModeService service,
            JsonMapper mapper) {
        super(tools, mapper, SERVER_NAME, GENERIC_INSTRUCTIONS);
        this.sessions = sessions;
        this.connections = connections;
        this.service = service;
    }

    /** @return the JSON response, empty for a notification */
    public Optional<String> handle(String ccmSessionId, byte[] body) {
        return respond(body, (tool, arguments) -> {
            var session = sessions.find(ccmSessionId)
                    .orElseThrow(() -> new IllegalStateException("This session is not managed by cc-manager anymore"));
            return tool.call(new SessionContext(session.projectId(), ccmSessionId), arguments);
        }, () -> instructions(ccmSessionId), tracking(ccmSessionId));
    }

    /** Only {@code execute} runs long enough to be cancelled; notifications/cancelled stops the run that very request starts, never another one. */
    private Tracking tracking(String ccmSessionId) {
        return new Tracking() {
            @Override
            public Handle started(String toolName, String requestId) {
                if (!ExecuteTool.NAME.equals(toolName)) {
                    return () -> { };
                }
                var request = new RunCancellation();
                var detach = request.attachToThread();
                var end = inFlight.begin(ccmSessionId, requestId, request::cancel);
                return () -> {
                    end.run();
                    detach.run();
                };
            }

            @Override
            public void cancelled(String requestId) {
                inFlight.cancel(ccmSessionId, requestId);
            }
        };
    }

    /** Names the session's servers without connecting them; falls back to the generic text when the session is unknown or ended. */
    private String instructions(String ccmSessionId) {
        var session = sessions.find(ccmSessionId);
        if (session.isEmpty()) {
            return GENERIC_INSTRUCTIONS;
        }
        try {
            return instructionsFor(connections.aliases(new SessionContext(session.get().projectId(), ccmSessionId)));
        } catch (RuntimeException e) {
            log.warn("Code Mode instructions fell back to the generic text: {}", e.getClass().getSimpleName());
            return GENERIC_INSTRUCTIONS;
        }
    }

    static String instructionsFor(List<String> aliases) {
        if (aliases.isEmpty()) {
            return GENERIC_INSTRUCTIONS;
        }
        return "Code Mode is on in this project. These MCP servers are routed through Code Mode and their tools are NOT available directly: "
                + ServerNames.listed(aliases) + ". "
                + "To use any of them, or when a tool or server of this kind is missing or unknown (for example mcp__<plugin>_<server>__* or a server name you know), "
                + "do not start or call it yourself: call list_servers (names, status), then search_tools (TypeScript signatures), "
                + "then execute with a JavaScript script that calls `await servers.<alias>.<tool>(args)`. "
                + "Chain many calls in ONE execute and return only a small result; write big data to files instead of returning it. "
                + "Images arrive as {type:'image', path}. servers.<alias> also accepts the saved name shown by list_servers.";
    }
}
