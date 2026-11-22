package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.astrolabe.toolchain.managed.SafeArchiveExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Linux tar.gz installs: unpacks the verified archive into a private folder next to it and, once the app has exited, runs the
 * archive's own per-user {@code install.sh} (which swaps the app folder and refreshes the menu entry), then relaunches the app.
 */
@Component
class TarGzUpdater implements PlatformUpdater {

    static final String FRAGMENT = "step install sh \"$1/install.sh\"";

    private static final String LAUNCHER = "Astrolabe Studio/bin/Astrolabe Studio";
    private static final String INSTALL_SCRIPT = "install.sh";
    private static final String ICON = "icon.png";
    private static final String INSTALL_PARENT = ".local/opt";

    private final Path home;
    private final UpdateProperties properties;
    private final SafeArchiveExtractor extractor = new SafeArchiveExtractor(false);

    @Autowired
    TarGzUpdater(UpdateProperties properties) {
        this(Path.of(System.getProperty("user.home")), properties);
    }

    TarGzUpdater(Path home, UpdateProperties properties) {
        this.home = home;
        this.properties = properties;
    }

    @Override
    public InstallKind kind() {
        return InstallKind.TAR_GZ;
    }

    /** The per-user install folder's parent (or its nearest existing ancestor, which install.sh creates it under) must be writable. */
    @Override
    public boolean supported(Installation installation) {
        if (installation.kind() != InstallKind.TAR_GZ) {
            return false;
        }
        var existing = home.resolve(INSTALL_PARENT);
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing != null && Files.isDirectory(existing) && Files.isWritable(existing);
    }

    @Override
    public boolean needsAuthentication() {
        return false;
    }

    /** @throws IOException when the archive differs from the verified hash, is unsafe or lacks the launcher, {@code install.sh} or {@code icon.png} */
    @Override
    public Path prepare(Path verifiedDownload, String verifiedSha256, Installation installation) throws IOException {
        UpdateFiles.requireSha256(verifiedDownload, verifiedSha256, "The archive");
        var directory = verifiedDownload.toAbsolutePath().resolveSibling("unpacked-" + UUID.randomUUID());
        OwnerOnlyFiles.createDirectory(directory);
        var ready = false;
        try {
            extractor.extract(verifiedDownload, directory, true, properties.archiveMaxEntries(), properties.archiveMaxUnpackedBytes());
            var launcher = directory.resolve(LAUNCHER);
            if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
                throw new IOException("The archive has no executable launcher");
            }
            for (var name : List.of(INSTALL_SCRIPT, ICON)) {
                if (!Files.isRegularFile(directory.resolve(name))) {
                    throw new IOException("The archive has no " + name);
                }
            }
            ready = true;
            return directory;
        } finally {
            if (!ready) {
                UpdateFiles.deleteQuietly(directory);
            }
        }
    }

    /** @throws IllegalStateException when the installer script cannot be started */
    @Override
    public void launchAfterExit(Path unpacked, Installation installation) {
        var updatesDir = unpacked.toAbsolutePath().getParent();
        PosixUpdateScript.launch(updatesDir, ProcessHandle.current().pid(), installation.appExecutable(), FRAGMENT,
                List.of(unpacked.toAbsolutePath().toString()), List.of(unpacked), properties);
    }
}
