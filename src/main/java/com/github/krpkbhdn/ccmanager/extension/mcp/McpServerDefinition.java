package com.github.krpkbhdn.ccmanager.extension.mcp;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A configured MCP server with everything needed to connect, secrets included: never log it; {@link #toString} hides env and header values.
 * {@code workingDirectory} is where a stdio server starts (the project folder, like Claude Code does); null means the app's own.
 */
public record McpServerDefinition(String name, ExtensionScope scope, Transport transport, String command, List<String> args, Map<String, String> env,
        String url, Map<String, String> headers, Path workingDirectory) {

    public enum Transport { STDIO, HTTP }

    public McpServerDefinition {
        args = List.copyOf(args);
        env = Map.copyOf(env);
        headers = Map.copyOf(headers);
    }

    @Override
    public String toString() {
        return "McpServerDefinition[name=" + name + ", scope=" + scope + ", transport=" + transport + ", command=" + command + ", args=" + args.size()
                + " args, env=" + env.keySet() + ", url=" + McpTargetRedaction.redact(url) + ", headers=" + headers.keySet() + "]";
    }
}
