package com.github.krpkbhdn.astrolabe.codemode.client;

/** A remote MCP server could not be reached or answered with an error; the message never carries configured secrets. */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
