package com.github.krpkbhdn.astrolabe.claude.proposal;

import com.github.krpkbhdn.astrolabe.claude.event.HookEvent;
import com.github.krpkbhdn.astrolabe.claude.event.HookEventType;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** An MCP server asking the user for input (Elicitation). url and elicitationId are empty when absent. Not verified against a live claude. */
public record FormProposal(String id, String astrolabeSessionId, Instant createdAt, String agentId, String serverName, String message,
        String mode, String url, String elicitationId, JsonNode requestedSchema) implements Proposal {

    @Override
    public String toolName() {
        return "";
    }

    @Override
    public boolean settledBy(HookEvent event) {
        return Proposal.super.settledBy(event) || event.type() == HookEventType.ELICITATION_RESULT;
    }
}
