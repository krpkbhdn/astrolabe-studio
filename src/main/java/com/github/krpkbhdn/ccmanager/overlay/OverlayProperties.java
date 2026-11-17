package com.github.krpkbhdn.ccmanager.overlay;

import static com.github.krpkbhdn.ccmanager.config.PropertyRules.positive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the project overlay (cc-manager.overlay): [sweepGrace] is how old an unreferenced overlay folder must be before the startup
 * sweep deletes it; [maxAgentsJsonChars] is the escaped size of --agents above which agents travel as a plugin (a Windows command line holds
 * about 32,000 characters, the rest goes to other flags and the task text).
 */
@ConfigurationProperties("cc-manager.overlay")
public record OverlayProperties(@DefaultValue("1d") Duration sweepGrace, @DefaultValue("20000") int maxAgentsJsonChars) {

    public OverlayProperties {
        positive("cc-manager.overlay.sweep-grace", sweepGrace);
        positive("cc-manager.overlay.max-agents-json-chars", maxAgentsJsonChars);
    }
}
