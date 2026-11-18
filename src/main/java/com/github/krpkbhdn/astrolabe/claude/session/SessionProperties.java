package com.github.krpkbhdn.astrolabe.claude.session;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of session tracking: how long a running subagent may stay silent before it is shown as inactive, and how
 * long a working session may stay silent before the all-sessions screen flags it. Zero disables the check.
 */
@ConfigurationProperties("astrolabe.sessions")
public record SessionProperties(@DefaultValue("10m") Duration subagentStaleAfter, @DefaultValue("10m") Duration workingStaleAfter) {

    public SessionProperties {
        nonNegative("astrolabe.sessions.subagent-stale-after", subagentStaleAfter);
        nonNegative("astrolabe.sessions.working-stale-after", workingStaleAfter);
    }
}
