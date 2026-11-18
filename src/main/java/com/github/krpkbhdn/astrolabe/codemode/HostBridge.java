package com.github.krpkbhdn.astrolabe.codemode;

/** What a running script may call back into the app; every call is checked and audited by the implementation. */
public interface HostBridge {

    /** @return the tool result as a JSON string; @throws RuntimeException when the call is denied or fails */
    String callTool(String server, String tool, String argsJson);

    void log(String line);

    /** @return JSON {@code {"names": {spelling: alias}, "available": "a, b"}} the script's {@code servers} object checks names against, or null for no check */
    default String serverIndexJson() {
        return null;
    }
}
