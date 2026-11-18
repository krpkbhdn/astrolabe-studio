package com.github.krpkbhdn.astrolabe.codemode.client;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The app as MCP client of one remote server. */
public interface McpClient extends AutoCloseable {

    void initialize();

    List<RemoteTool> listTools();

    /** @return the tool result (the {@code result} of tools/call) */
    JsonNode callTool(String tool, JsonNode arguments);

    @Override
    void close();
}
