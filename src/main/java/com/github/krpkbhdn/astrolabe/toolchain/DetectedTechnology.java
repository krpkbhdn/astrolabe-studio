package com.github.krpkbhdn.astrolabe.toolchain;

/**
 * A technology found in a project folder, its Claude LSP plugin state and, when that plugin is on, the probe state of its language server
 * (null otherwise: not probed); {@code detail} is the reason of a FAILED state, empty when unknown.
 */
public record DetectedTechnology(ToolDefinition tool, ToolState serverState, LspPluginState pluginState, String detail) {

    public DetectedTechnology(ToolDefinition tool, ToolState serverState, LspPluginState pluginState) {
        this(tool, serverState, pluginState, "");
    }

    /** A language without a plugin, judged by its server alone. */
    public DetectedTechnology(ToolDefinition tool, ToolState serverState) {
        this(tool, serverState, LspPluginState.NOT_NEEDED);
    }

    public DetectedTechnology withServerState(ToolState state) {
        return new DetectedTechnology(tool, state, pluginState, state == ToolState.FAILED ? detail : "");
    }

    public DetectedTechnology withFailure(String reason) {
        return new DetectedTechnology(tool, ToolState.FAILED, pluginState, reason);
    }
}
