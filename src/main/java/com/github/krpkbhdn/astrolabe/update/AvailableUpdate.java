package com.github.krpkbhdn.astrolabe.update;

import java.net.URI;

/**
 * A published release newer than the running app, with the installer for this installation, its SHA-256 file and its detached
 * {@code .asc} signature (an update without all three is {@link #releasePageOnly}).
 *
 * @param installerName, installerUri, checksumUri null when not {@link #installable()}
 * @param installerSizeBytes the advertised size, 0 when the release does not say
 */
public record AvailableUpdate(String version, String installerName, long installerSizeBytes, URI installerUri, URI checksumUri, URI releasePageUri) {

    /** A release only reachable through its page: this installation has no known installer asset. */
    public static AvailableUpdate releasePageOnly(String version, URI releasePageUri) {
        return new AvailableUpdate(version, null, 0, null, null, releasePageUri);
    }

    /** False for [releasePageOnly]: there is nothing to download. */
    public boolean installable() {
        return installerUri != null;
    }

    /** Only meaningful when {@link #installable()}. */
    public URI signatureUri() {
        return URI.create(installerUri.toString() + ".asc");
    }
}
