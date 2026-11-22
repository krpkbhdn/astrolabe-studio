package com.github.krpkbhdn.astrolabe.update;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The one test source of {@link UpdateProperties} values: the defaults come from the {@code @DefaultValue}s, so they cannot drift. */
final class TestUpdateProperties {

    private TestUpdateProperties() {
    }

    static UpdateProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("astrolabe.update", UpdateProperties.class);
    }

    /** The defaults with the first six values replaced. */
    static UpdateProperties of(String version, String repository, String apiBaseUrl, int checkIntervalHours, int requestTimeoutSeconds, Duration queryTimeout) {
        var d = defaults();
        return new UpdateProperties(version, repository, apiBaseUrl, checkIntervalHours, requestTimeoutSeconds, queryTimeout, d.pidWaitTimeout(),
                d.pidKillGrace(), d.hdiutilAttachTimeout(), d.hdiutilDetachTimeout(), d.dittoTimeout(), d.archiveMaxEntries(), d.archiveMaxUnpackedBytes(),
                d.maxInstallerBytes(), d.maxLogBytes(), d.detectionRetryBackoff());
    }
}
