package com.github.krpkbhdn.ccmanager.codemode.client;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import org.springframework.stereotype.Component;

/** Creates the client matching a server definition's transport; the caller initializes and closes it. */
@Component
public class McpClients {

    private final CodeModeProperties properties;
    private final HostEnvironment host;

    McpClients(CodeModeProperties properties, HostEnvironment host) {
        this.properties = properties;
        this.host = host;
    }

    public McpClient create(McpServerDefinition definition) {
        return switch (definition.transport()) {
            case STDIO -> new StdioMcpClient(definition, properties, host);
            case HTTP -> new HttpMcpClient(definition, properties);
        };
    }
}
