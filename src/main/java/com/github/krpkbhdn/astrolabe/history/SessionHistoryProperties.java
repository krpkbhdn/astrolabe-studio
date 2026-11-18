package com.github.krpkbhdn.astrolabe.history;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the session history index: how often transcripts are scanned, how many entries the index may hold, how
 * many hits a search returns, how many entries one eviction transaction deletes and how many bytes and lines one
 * indexing transaction takes (small, so the single SQLite writer lock is held briefly), and how many sensitive tool ids
 * a transcript remembers (to skip their results, even duplicated ones).
 */
@ConfigurationProperties("astrolabe.history")
public record SessionHistoryProperties(@DefaultValue("30s") Duration scanInterval, @DefaultValue("200000") int maxEntries, @DefaultValue("100") int searchLimit,
        @DefaultValue("5000") int evictChunk, @DefaultValue("1048576") long batchBytes, @DefaultValue("300") int batchLines,
        @DefaultValue("1000") int maxSensitiveToolIds) {

    public SessionHistoryProperties {
        positive("astrolabe.history.scan-interval", scanInterval);
        positive("astrolabe.history.max-entries", maxEntries);
        positive("astrolabe.history.search-limit", searchLimit);
        positive("astrolabe.history.evict-chunk", evictChunk);
        positive("astrolabe.history.batch-bytes", batchBytes);
        positive("astrolabe.history.batch-lines", batchLines);
        positive("astrolabe.history.max-sensitive-tool-ids", maxSensitiveToolIds);
    }
}
