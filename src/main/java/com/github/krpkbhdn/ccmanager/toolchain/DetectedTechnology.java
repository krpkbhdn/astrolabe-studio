package com.github.krpkbhdn.ccmanager.toolchain;

/** A technology found in a project folder and the probe state of its language server on this machine. */
public record DetectedTechnology(ToolDefinition tool, ToolState serverState) {
}
