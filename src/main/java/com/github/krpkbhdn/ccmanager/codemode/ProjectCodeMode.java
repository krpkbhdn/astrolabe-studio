package com.github.krpkbhdn.ccmanager.codemode;

import java.util.List;

/** Code Mode setting of one project: whether it is on and which MCP servers the app talks to for it, and which tool calls run without asking. */
public record ProjectCodeMode(long projectId, boolean enabled, List<String> servers, ToolCallAccess toolCalls) {

    public ProjectCodeMode {
        servers = List.copyOf(servers);
    }
}
