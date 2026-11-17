package com.github.krpkbhdn.astrolabe.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the app keeps its local state (SQLite file). */
@ConfigurationProperties("astrolabe")
public record AppProperties(Path dataDir) {}
