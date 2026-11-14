package com.github.krpkbhdn.ccmanager.codemode;

/** The Claude session a script runs for (audit and permission scope). */
public record SessionContext(long projectId, String sessionId) {
}
