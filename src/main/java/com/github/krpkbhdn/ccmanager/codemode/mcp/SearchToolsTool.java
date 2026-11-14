package com.github.krpkbhdn.ccmanager.codemode.mcp;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class SearchToolsTool implements CodeModeTool {

    private final CodeModeService service;

    SearchToolsTool(CodeModeService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "search_tools";
    }

    @Override
    public String description() {
        return "Searches the tools of the project's MCP servers (reachable only through Code Mode) and returns their TypeScript signatures to call from execute "
                + "as `await servers.<alias>.<tool>(args)`. An empty query lists every tool with a one-line summary. Optional server limits it to one server "
                + "(alias or saved name). Connects the servers on first use.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Words describing the tool; empty lists all\"},"
                + "\"server\":{\"type\":\"string\",\"description\":\"Only this server: its alias or saved name\"}}}";
    }

    @Override
    public String call(SessionContext session, JsonNode arguments) {
        return service.searchTools(session, arguments.path("query").asString("").strip(), arguments.path("server").asString("").strip());
    }
}
