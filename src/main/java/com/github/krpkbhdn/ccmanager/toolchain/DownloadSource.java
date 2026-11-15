package com.github.krpkbhdn.ccmanager.toolchain;

import java.util.Objects;

/** Download install of one OS; the checksum is always archiveUrl + ".sha256", {version} may appear in the archiveUrl path only. */
public record DownloadSource(LatestSource latest, String archiveUrl, ArchiveKind archive, LauncherSpec launcher) {

    public DownloadSource {
        Objects.requireNonNull(latest);
        Objects.requireNonNull(archiveUrl);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(launcher);
    }
}
