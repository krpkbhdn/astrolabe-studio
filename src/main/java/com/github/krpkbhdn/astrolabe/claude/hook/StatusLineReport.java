package com.github.krpkbhdn.astrolabe.claude.hook;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** The JSON a session's statusLine command posted; carries account-wide data such as rate limits, which hooks lack. */
public record StatusLineReport(String astrolabeSessionId, JsonNode payload, Instant receivedAt) {
}
