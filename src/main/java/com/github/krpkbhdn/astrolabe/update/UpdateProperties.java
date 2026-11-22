package com.github.krpkbhdn.astrolabe.update;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Self-update tunables; an empty version (a run from sources) turns the update check off. */
@ConfigurationProperties("astrolabe.update")
public record UpdateProperties(
        @DefaultValue("") String version,
        @DefaultValue("krpkbhdn/astrolabe-studio") String repository,
        @DefaultValue("https://api.github.com") String apiBaseUrl,
        @DefaultValue("6") int checkIntervalHours,
        @DefaultValue("15") int requestTimeoutSeconds,
        @DefaultValue("PT5S") Duration queryTimeout,
        @DefaultValue("PT60S") Duration pidWaitTimeout,
        @DefaultValue("PT5S") Duration pidKillGrace,
        @DefaultValue("PT2M") Duration hdiutilAttachTimeout,
        @DefaultValue("PT30S") Duration hdiutilDetachTimeout,
        @DefaultValue("PT5M") Duration dittoTimeout,
        @DefaultValue("100000") long archiveMaxEntries,
        @DefaultValue("4294967296") long archiveMaxUnpackedBytes,
        @DefaultValue("2147483648") long maxInstallerBytes,
        @DefaultValue("1048576") long maxLogBytes,
        @DefaultValue("PT30S") Duration detectionRetryBackoff) {

    public boolean enabled() {
        return !version.isBlank();
    }

    @ConstructorBinding
    public UpdateProperties {
        positive("astrolabe.update.check-interval-hours", checkIntervalHours);
        positive("astrolabe.update.request-timeout-seconds", requestTimeoutSeconds);
        positive("astrolabe.update.pid-wait-timeout", pidWaitTimeout);
        positive("astrolabe.update.pid-kill-grace", pidKillGrace);
        positive("astrolabe.update.hdiutil-attach-timeout", hdiutilAttachTimeout);
        positive("astrolabe.update.hdiutil-detach-timeout", hdiutilDetachTimeout);
        positive("astrolabe.update.ditto-timeout", dittoTimeout);
        positive("astrolabe.update.archive-max-entries", archiveMaxEntries);
        positive("astrolabe.update.archive-max-unpacked-bytes", archiveMaxUnpackedBytes);
        positive("astrolabe.update.max-installer-bytes", maxInstallerBytes);
        positive("astrolabe.update.max-log-bytes", maxLogBytes);
        positive("astrolabe.update.detection-retry-backoff", detectionRetryBackoff);
        if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("astrolabe.update.query-timeout must be positive");
        }
    }
}
