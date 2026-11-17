package com.github.krpkbhdn.astrolabe.codemode.mcp;

import com.github.krpkbhdn.astrolabe.claude.mcp.McpToolDescriptor;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import tools.jackson.databind.JsonNode;

/** A tool of the Code Mode server; deliberately not a McpTool, so the project server {@code astrolabe} never lists it. */
public interface CodeModeTool extends McpToolDescriptor {

    /**
     * @return text for Claude
     * @throws IllegalArgumentException or IllegalStateException with a message Claude can act on
     */
    String call(SessionContext session, JsonNode arguments);
}
