package com.github.krpkbhdn.astrolabe.codemode.client;

/** The server answered 401 or 403: it needs a sign-in the app cannot do, so the server is not eligible for Code Mode. */
public class McpAuthRequiredException extends McpClientException {

    public McpAuthRequiredException(int status) {
        super("The server requires authentication (HTTP " + status + ")");
    }
}
