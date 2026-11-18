package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.claude.event.HookEventType;
import java.time.Instant;

/** Human-readable line of the activity log; agentId is null for the main agent. */
public record ActivityEntry(Instant at, HookEventType type, String agentId, String text) {}
