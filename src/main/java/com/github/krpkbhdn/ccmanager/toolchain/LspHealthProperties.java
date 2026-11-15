package com.github.krpkbhdn.ccmanager.toolchain;

import static com.github.krpkbhdn.ccmanager.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the language server health check: each check may start a JVM per server, so the interval is long;
 * a managed tool is reinstalled at most once per cooldown.
 */
@ConfigurationProperties("cc-manager.toolchain.health")
public record LspHealthProperties(@DefaultValue("5m") Duration interval, @DefaultValue("2") int recoveryAttempts,
        @DefaultValue("10s") Duration recoveryBackoff, @DefaultValue("1h") Duration reinstallCooldown) {

    public LspHealthProperties {
        positive("cc-manager.toolchain.health.interval", interval);
        positive("cc-manager.toolchain.health.recovery-attempts", recoveryAttempts);
        positive("cc-manager.toolchain.health.recovery-backoff", recoveryBackoff);
        positive("cc-manager.toolchain.health.reinstall-cooldown", reinstallCooldown);
    }
}
