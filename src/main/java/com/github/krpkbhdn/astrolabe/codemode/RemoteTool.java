package com.github.krpkbhdn.astrolabe.codemode;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A tool of a remote MCP server as listed by tools/list. The hints come from its annotations (false when absent);
 * {@code destructiveHintDeclared} tells whether the server stated destructiveHint, as the spec makes it matter only for tools that are not read-only.
 */
public record RemoteTool(String server, String name, String description, JsonNode inputSchema, Optional<JsonNode> outputSchema,
        boolean readOnlyHint, boolean destructiveHint, boolean destructiveHintDeclared) {

    /** @param readOnly the annotation value, null when absent @param destructive the annotation value, null when absent (the spec default is true) */
    public static RemoteTool annotated(String server, String name, String description, JsonNode inputSchema, Optional<JsonNode> outputSchema,
            Boolean readOnly, Boolean destructive) {
        return new RemoteTool(server, name, description, inputSchema, outputSchema, Boolean.TRUE.equals(readOnly), destructive == null || destructive,
                destructive != null);
    }

    /** The same tool under the script alias of its server, the one identity grants and the catalog use. */
    public RemoteTool onServer(String alias) {
        return new RemoteTool(alias, name, description, inputSchema, outputSchema, readOnlyHint, destructiveHint, destructiveHintDeclared);
    }

    /** Read-only and not stated destructive: runs without asking. */
    public boolean isSafeToRunUnasked() {
        return readOnlyHint && !(destructiveHintDeclared && destructiveHint);
    }
}
