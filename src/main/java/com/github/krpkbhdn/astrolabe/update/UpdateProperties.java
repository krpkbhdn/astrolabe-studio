package com.github.krpkbhdn.astrolabe.update;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Self-update tunables; an empty version (a run from sources) turns the update check off. */
@ConfigurationProperties("astrolabe.update")
public record UpdateProperties(
        @DefaultValue("") String version,
        @DefaultValue("krpkbhdn/astrolabe-studio") String repository,
        @DefaultValue("https://api.github.com") String apiBaseUrl,
        @DefaultValue("6") int checkIntervalHours,
        @DefaultValue("15") int requestTimeoutSeconds) {

    public boolean enabled() {
        return !version.isBlank();
    }

    public UpdateProperties {
        positive("astrolabe.update.check-interval-hours", checkIntervalHours);
        positive("astrolabe.update.request-timeout-seconds", requestTimeoutSeconds);
    }
}
