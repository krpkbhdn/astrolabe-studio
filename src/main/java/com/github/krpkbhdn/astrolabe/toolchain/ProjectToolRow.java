package com.github.krpkbhdn.astrolabe.toolchain;

/** A language server as shown for one project; userLevelEnabled: its plugin is already on for the whole user. */
public record ProjectToolRow(ToolDefinition tool, ToolStatus status, boolean detected, boolean pluginEnabled, boolean userLevelEnabled) {}
