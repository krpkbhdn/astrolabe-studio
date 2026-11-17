package com.github.krpkbhdn.astrolabe.codemode;

import java.util.List;

/** Which tool calls of a project's Code Mode servers run without asking: none (ASK), every server, or the chosen ones (by saved name or script alias). */
public record ToolCallAccess(Mode mode, List<String> servers) {

    public enum Mode { ASK, ALL, SELECTED }

    public static final ToolCallAccess ASK = new ToolCallAccess(Mode.ASK, List.of());
    public static final ToolCallAccess ALL = new ToolCallAccess(Mode.ALL, List.of());

    public ToolCallAccess {
        servers = mode == Mode.SELECTED ? List.copyOf(servers) : List.of();
    }

    public static ToolCallAccess selected(List<String> servers) {
        return new ToolCallAccess(Mode.SELECTED, servers);
    }

    public boolean allows(String alias, String savedName) {
        return mode == Mode.ALL || mode == Mode.SELECTED && (servers.contains(alias) || servers.contains(savedName));
    }
}
