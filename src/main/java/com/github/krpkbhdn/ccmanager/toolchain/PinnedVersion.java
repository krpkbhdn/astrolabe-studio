package com.github.krpkbhdn.ccmanager.toolchain;

import java.util.Objects;

/** A fixed release of a managed tool: the archive url is used as is and verified against the given SHA-256. */
public record PinnedVersion(String version, String url, String sha256) {

    public PinnedVersion {
        Objects.requireNonNull(version);
        Objects.requireNonNull(url);
        Objects.requireNonNull(sha256);
    }
}
