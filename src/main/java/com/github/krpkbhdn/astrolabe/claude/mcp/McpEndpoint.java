package com.github.krpkbhdn.astrolabe.claude.mcp;

import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The project MCP server {@code astrolabe} that astrolabe hands to every project session it launches; it knows only {@link McpTool}s. */
@Component
public class McpEndpoint extends McpJsonRpcServer<McpTool> {

    public static final String SERVER_NAME = "astrolabe";
    static final String INSTRUCTIONS = "This server belongs to Astrolabe Studio, the desktop app running this session. "
            + "Use its run configuration tools so the user can start the app from astrolabe. "
            + "See the astrolabe:run-configurations skill for when to use them. "
            + "Use submit_screenshots to hand screenshots to the user for visual review; see the astrolabe:visual-review skill.";

    private final SessionRegistry sessions;

    public McpEndpoint(List<McpTool> tools, SessionRegistry sessions, JsonMapper mapper) {
        super(tools, mapper, SERVER_NAME, INSTRUCTIONS);
        this.sessions = sessions;
    }

    /** @return the JSON response, empty for a notification */
    public Optional<String> handle(String astrolabeSessionId, byte[] body) {
        return respond(body, (tool, arguments) -> {
            var session = sessions.find(astrolabeSessionId)
                    .orElseThrow(() -> new IllegalStateException("This session is not managed by astrolabe anymore"));
            return tool.call(session.projectId(), astrolabeSessionId, arguments);
        });
    }
}
