package com.github.krpkbhdn.astrolabe.toolchain;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the language server health check: each check may start a JVM per server, so the interval is long;
 * a managed tool is reinstalled at most once per cooldown; a Gradle project that jdtls has not imported yet is looked at again after {@code markerRecheck}, and FAILED when still without a marker after {@code markerWait}.
 */
@ConfigurationProperties("astrolabe.toolchain.health")
public record LspHealthProperties(@DefaultValue("5m") Duration interval, @DefaultValue("2") int recoveryAttempts,
        @DefaultValue("10s") Duration recoveryBackoff, @DefaultValue("1h") Duration reinstallCooldown,
        @DefaultValue("30s") Duration markerRecheck, @DefaultValue("10m") Duration markerWait) {

    public LspHealthProperties {
        positive("astrolabe.toolchain.health.marker-recheck", markerRecheck);
        positive("astrolabe.toolchain.health.marker-wait", markerWait);
        positive("astrolabe.toolchain.health.interval", interval);
        positive("astrolabe.toolchain.health.recovery-attempts", recoveryAttempts);
        positive("astrolabe.toolchain.health.recovery-backoff", recoveryBackoff);
        positive("astrolabe.toolchain.health.reinstall-cooldown", reinstallCooldown);
    }
}
