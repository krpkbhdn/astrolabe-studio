package com.github.krpkbhdn.astrolabe.codemode.mcp;

import com.github.krpkbhdn.astrolabe.claude.mcp.McpToolDescriptor;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeService;
import com.github.krpkbhdn.astrolabe.codemode.RunCancellation;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Not pre-approved on purpose: the permission dialog of Claude Code itself shows the script before it runs. */
@Component
class ExecuteTool implements CodeModeTool {

    static final String NAME = "execute";
    static final String TRUNCATED_NOTE = "\n[output truncated]";

    private final CodeModeService service;

    ExecuteTool(CodeModeService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Runs a JavaScript snippet (not TypeScript) that calls the project's MCP tools, which are reachable only through Code Mode (see list_servers): "
                + "`const r = await servers.<server>.<tool>({...args}); return r;`. Find tools and signatures with search_tools first. "
                + "The code is the body of an async function: return the result or use console.log, and keep what you return small, "
                + "filter and summarize inside the script. A tool result is parsed JSON, or text when it is not JSON. "
                + "Images arrive as {type:'image', mimeType, path}: files you can read from disk. Calls that change things may ask the user.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"description\":\"JavaScript, the body of an async function\"}},\"required\":[\"code\"]}";
    }

    @Override
    public String call(SessionContext session, JsonNode arguments) {
        var result = service.execute(session, McpToolDescriptor.requiredText(arguments, "code"), RunCancellation.current());
        var text = result.output() + (result.truncated() ? TRUNCATED_NOTE : "");
        if (result.error().isPresent()) {
            throw new IllegalStateException((text.isBlank() ? "" : text + "\n") + "Error: " + result.error().get());
        }
        return text.isBlank() ? "(no output)" : text;
    }
}
