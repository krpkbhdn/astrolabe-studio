package com.github.krpkbhdn.ccmanager.codemode.mcp;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.ServerNames;
import com.github.krpkbhdn.ccmanager.codemode.ServerStatus;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.review.PromptText;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class ListServersTool implements CodeModeTool {

    private final CodeModeService service;

    ListServersTool(CodeModeService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "list_servers";
    }

    @Override
    public String description() {
        return "Lists the MCP servers this project reaches only through Code Mode (their own tools are not available directly): script alias, saved name, scope, "
                + "transport and whether each is connected or unavailable (for example needs sign-in). Next: search_tools, then execute.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String call(SessionContext session, JsonNode arguments) {
        var servers = service.listServers(session);
        if (servers.isEmpty()) {
            return "No MCP servers are configured for Code Mode in this project.";
        }
        return servers.stream().map(ListServersTool::line).collect(Collectors.joining("\n"))
                + "\nIn execute call a server as `servers.<alias>.<tool>(args)`; the saved name works too.";
    }

    private static String line(ServerStatus server) {
        var saved = server.stored().equals(server.name()) ? "" : "saved as " + ServerNames.shown(server.stored()) + ", ";
        return PromptText.oneLine(ServerNames.shown(server.name()) + " (" + saved + server.scope() + ", " + server.transport() + "): "
                + server.problem().map(problem -> "unavailable, " + problem).orElse(server.connected() ? "connected" : "ready, connects on first use"));
    }
}
