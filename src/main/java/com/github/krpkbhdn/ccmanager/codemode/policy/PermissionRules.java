package com.github.krpkbhdn.ccmanager.codemode.policy;

import com.github.krpkbhdn.ccmanager.codemode.SessionContext;

/** What the user's Claude Code permission rules say about a remote MCP tool, so Code Mode does not ask again for what Claude Code already allows or forbids. */
public interface PermissionRules {

    /** Checked in the order deny, ask, allow; ASK also stands for a rule form or settings file that could not be understood, so nothing is allowed on a guess. */
    enum Verdict { DENY, ASK, ALLOW, UNDECIDED }

    PermissionRules NONE = (session, server, tool) -> Verdict.UNDECIDED;

    /** @param server the name Claude Code knows the server by (its saved name, {@code plugin_<plugin>_<server>} for a plugin) */
    Verdict verdict(SessionContext session, String server, String tool);
}
