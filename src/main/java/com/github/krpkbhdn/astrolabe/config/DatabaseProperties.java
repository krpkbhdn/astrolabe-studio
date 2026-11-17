package com.github.krpkbhdn.astrolabe.config;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables of the SQLite connection pool: how long a writer waits for the write lock and how many connections may be open. */
@ConfigurationProperties("astrolabe.database")
public record DatabaseProperties(@DefaultValue("5s") Duration busyTimeout, @DefaultValue("4") int maxConnections) {

    public DatabaseProperties {
        positive("astrolabe.database.busy-timeout", busyTimeout);
        positive("astrolabe.database.max-connections", maxConnections);
    }
}
