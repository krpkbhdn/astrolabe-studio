package com.github.krpkbhdn.astrolabe.gitlab;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings of the GitLab CLI calls. */
@ConfigurationProperties("astrolabe.gitlab")
public record GlabProperties(
        @DefaultValue("glab") String executable,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("10m") Duration readyTtl) {

    public GlabProperties {
        positive("astrolabe.gitlab.timeout", timeout);
        positive("astrolabe.gitlab.ready-ttl", readyTtl);
    }
}
