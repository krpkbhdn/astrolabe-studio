package com.github.krpkbhdn.astrolabe.update;

import java.io.IOException;
import java.nio.file.Path;

/** Installs a downloaded release the way one {@link InstallKind} needs; the service picks the bean matching the detected installation. */
interface PlatformUpdater {

    InstallKind kind();

    boolean supported(Installation installation);

    /** True when installing asks the user for an administrator password (deb/rpm installed system-wide). */
    boolean needsAuthentication();

    /**
     * @param verifiedSha256 the SHA-256 (hex) the service verified for {@code verifiedDownload}; an updater that copies or reads the file
     *                       compares those bytes against it again and never hashes the source to learn it
     * @return the file or folder {@link #launchAfterExit} works on, made from the verified download
     */
    Path prepare(Path verifiedDownload, String verifiedSha256, Installation installation) throws IOException;

    /** The install starts once this process has exited; the caller must quit right after.
     * @throws IllegalStateException when the installer cannot be started */
    void launchAfterExit(Path prepared, Installation installation);

    /** The staged [prepared] is being thrown away; an updater that keeps per-file state forgets it. */
    default void discard(Path prepared) {
    }
}
