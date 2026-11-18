package com.github.krpkbhdn.astrolabe.toolchain;

import java.util.Objects;
import java.util.Optional;

/** Download install of one OS; the checksum is always archiveUrl + ".sha256", {version} may appear in the archiveUrl path only; pinned is an optional fixed release (null when absent). */
public record DownloadSource(LatestSource latest, String archiveUrl, ArchiveKind archive, LauncherSpec launcher,
                             PinnedVersion pinned) {

    public DownloadSource(LatestSource latest, String archiveUrl, ArchiveKind archive, LauncherSpec launcher) {
        this(latest, archiveUrl, archive, launcher, null);
    }

    public DownloadSource {
        Objects.requireNonNull(latest);
        Objects.requireNonNull(archiveUrl);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(launcher);
    }

    public Optional<PinnedVersion> pinnedVersion() {
        return Optional.ofNullable(pinned);
    }
}
