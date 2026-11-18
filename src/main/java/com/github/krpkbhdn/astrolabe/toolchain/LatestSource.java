package com.github.krpkbhdn.astrolabe.toolchain;

import java.util.Objects;

/** Where the newest version comes from: the body of a text file or the Location header of a redirect; pattern has exactly one group, the version. */
public record LatestSource(Kind kind, String url, String pattern) {

    public enum Kind { TEXT_FILE, REDIRECT }

    public LatestSource {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(url);
        Objects.requireNonNull(pattern);
    }
}
