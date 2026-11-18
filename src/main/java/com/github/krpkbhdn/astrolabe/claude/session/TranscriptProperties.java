package com.github.krpkbhdn.astrolabe.claude.session;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Transcript reading limits: entries returned, bytes read from the file tail on first and later title reads, and the byte window around a jump target. */
@ConfigurationProperties("astrolabe.transcript")
public record TranscriptProperties(
        @DefaultValue("300") int maxEntries,
        @DefaultValue("1048576") int tailBytes,
        @DefaultValue("65536") int refreshTailBytes,
        @DefaultValue("524288") int windowBytes) {

    public TranscriptProperties {
        positive("astrolabe.transcript.max-entries", maxEntries);
        positive("astrolabe.transcript.tail-bytes", tailBytes);
        positive("astrolabe.transcript.refresh-tail-bytes", refreshTailBytes);
        positive("astrolabe.transcript.window-bytes", windowBytes);
    }
}
