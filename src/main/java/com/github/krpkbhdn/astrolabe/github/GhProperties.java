package com.github.krpkbhdn.astrolabe.github;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings of the GitHub CLI calls. */
@ConfigurationProperties("astrolabe.github")
public record GhProperties(
        @DefaultValue("gh") String executable,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("20s") Duration ciPollInterval,
        @DefaultValue("10m") Duration readyTtl) {

    public GhProperties {
        positive("astrolabe.github.timeout", timeout);
        positive("astrolabe.github.ci-poll-interval", ciPollInterval);
        positive("astrolabe.github.ready-ttl", readyTtl);
    }
}
