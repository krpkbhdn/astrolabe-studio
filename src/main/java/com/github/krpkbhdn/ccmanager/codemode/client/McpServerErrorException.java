package com.github.krpkbhdn.ccmanager.codemode.client;

/** The server itself answered a request with a JSON-RPC error (invalid params, tool failure); the connection is healthy, unlike after a transport failure. */
public class McpServerErrorException extends McpClientException {

    static final int MAX_MESSAGE_CHARS = 2000;

    public McpServerErrorException(int code, String serverMessage) {
        super("The server answered with an error " + code + ": " + capped(serverMessage));
    }

    /** Server text can be megabytes; it ends up in failures and listings, so it is cut. */
    static String capped(String text) {
        return text.length() <= MAX_MESSAGE_CHARS ? text : text.substring(0, MAX_MESSAGE_CHARS) + "...";
    }
}
