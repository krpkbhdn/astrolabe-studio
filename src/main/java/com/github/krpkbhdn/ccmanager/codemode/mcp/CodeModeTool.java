package com.github.krpkbhdn.ccmanager.codemode.mcp;

import com.github.krpkbhdn.ccmanager.claude.mcp.McpToolDescriptor;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import tools.jackson.databind.JsonNode;

/** A tool of the Code Mode server; deliberately not a McpTool, so the project server {@code cc-manager} never lists it. */
public interface CodeModeTool extends McpToolDescriptor {

    /**
     * @return text for Claude
     * @throws IllegalArgumentException or IllegalStateException with a message Claude can act on
     */
    String call(SessionContext session, JsonNode arguments);
}
