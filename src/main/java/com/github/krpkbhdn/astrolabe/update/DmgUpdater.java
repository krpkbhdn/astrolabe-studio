package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * macOS: mounts the downloaded dmg read-only, copies its single {@code .app} next to the installed bundle with {@code ditto} (keeps the
 * symlinks, xattrs and permissions the JCEF frameworks need) and unmounts. After exit a detached script swaps the bundle and restores
 * the old one when the swap fails.
 */
@Component
class DmgUpdater implements PlatformUpdater {

    /** Runs one argv with a timeout; a seam so tests need no macOS. */
    @FunctionalInterface
    interface Runner {
        CommandRunner.Result run(List<String> command, Duration timeout) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(DmgUpdater.class);
    private static final String UPDATES_DIRECTORY = "updates";
    private static final String APP_SUFFIX = ".app";
    private static final String MOUNT_DIRECTORY = "mnt";
    private static final String WORK_PREFIX = "astrolabe-mount-";
    private static final String HDIUTIL = "/usr/bin/hdiutil";
    private static final String DITTO = "/usr/bin/ditto";
    private static final String DEFAULTS = "/usr/bin/defaults";
    private static final String BUNDLE_ID_KEY = "CFBundleIdentifier";
    private static final String INFO_PLIST = "Contents/Info";

    /** Arguments: "$1" installed bundle, "$2" staged bundle. Restores the old bundle when the move fails and reports the failed step as install. */
    static final String FRAGMENT = """
            old="$1.old"
            rm -rf "$old"
            step backup mv "$1" "$old" || return 1
            if ! step install mv "$2" "$1"; then
              rm -rf "$1"
              step restore mv "$old" "$1" || return 1
              rm -rf "$2"
              STEP=install
              return 1
            fi
            step cleanup rm -rf "$old"
            return 0
            """;

    private final Path updatesDir;
    private final Runner runner;
    private final UpdateProperties properties;
    private final Path workRoot;

    @Autowired
    DmgUpdater(AppProperties app, UpdateProperties properties) {
        this(app.dataDir().resolve(UPDATES_DIRECTORY), (command, timeout) -> CommandRunner.run(command, null, timeout), properties,
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    DmgUpdater(Path updatesDir, Runner runner, UpdateProperties properties, Path workRoot) {
        this.updatesDir = updatesDir;
        this.runner = runner;
        this.properties = properties;
        this.workRoot = workRoot;
    }

    @Override
    public InstallKind kind() {
        return InstallKind.DMG;
    }

    /** The user must be able to replace the bundle without admin rights; otherwise the UI points to the release page. */
    @Override
    public boolean supported(Installation installation) {
        var parent = installation.root().getParent();
        return installation.kind() == InstallKind.DMG && parent != null && Files.isWritable(installation.root()) && Files.isWritable(parent);
    }

    @Override
    public boolean needsAuthentication() {
        return false;
    }

    @Override
    public Path prepare(Path dmg, String verifiedSha256, Installation installation) throws IOException {
        UpdateFiles.requireSha256(dmg, verifiedSha256, "The disk image");
        var bundle = installation.root().toAbsolutePath();
        var name = bundle.getFileName().toString();
        Files.createDirectories(updatesDir);
        // outside the updates folder: a mount that cannot be detached must never sit inside a folder that is cleaned recursively
        var work = workRoot.resolve(WORK_PREFIX + UUID.randomUUID());
        OwnerOnlyFiles.createDirectory(work);
        var mount = work.resolve(MOUNT_DIRECTORY);
        OwnerOnlyFiles.createDirectory(mount);
        var attached = false;
        try {
            require(runner.run(List.of(HDIUTIL, "attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mount.toString(),
                    dmg.toAbsolutePath().toString()), properties.hdiutilAttachTimeout()), "mount the disk image");
            attached = true;
            var source = singleApp(mount, name);
            var staged = bundle.resolveSibling("." + name + ".update");
            UpdateFiles.deleteTree(staged);
            require(runner.run(List.of(DITTO, source.toString(), staged.toString()), properties.dittoTimeout()), "copy the app");
            try {
                requireSameBundleId(bundle, staged);
            } catch (IOException e) {
                UpdateFiles.deleteTree(staged);
                throw e;
            }
            return staged;
        } finally {
            var detached = !attached || detach(mount);
            if (detached) {
                try {
                    UpdateFiles.deleteTree(work);
                } catch (IOException e) {
                    log.warn("Could not remove the disk image work folder: {}", e.getClass().getSimpleName());
                }
            }
        }
    }

    @Override
    public void launchAfterExit(Path staged, Installation installation) {
        // shortcut: the relaunch execs the executable inside the bundle, not `open`, because PosixUpdateScript only runs "$APP"
        PosixUpdateScript.launch(updatesDir, ProcessHandle.current().pid(), installation.appExecutable(), FRAGMENT,
                List.of(installation.root().toAbsolutePath().toString(), staged.toAbsolutePath().toString()), List.of(), properties);
    }

    private Path singleApp(Path mount, String expectedName) throws IOException {
        List<Path> apps;
        try (var entries = Files.list(mount)) {
            apps = entries.filter(entry -> entry.getFileName().toString().endsWith(APP_SUFFIX)).toList();
        }
        if (apps.size() != 1) {
            throw new IOException("The disk image must hold exactly one app, found " + apps.size());
        }
        if (!apps.getFirst().getFileName().toString().equals(expectedName)) {
            throw new IOException("The disk image holds " + apps.getFirst().getFileName() + ", not " + expectedName);
        }
        return apps.getFirst();
    }

    private boolean detach(Path mount) {
        for (var command : List.of(List.of(HDIUTIL, "detach", mount.toString()), List.of(HDIUTIL, "detach", "-force", mount.toString()))) {
            try {
                if (runner.run(command, properties.hdiutilDetachTimeout()).succeeded()) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Could not run hdiutil detach: {}", e.toString());
            }
        }
        log.warn("The update disk image stays mounted at {}", mount);
        return false;
    }

    /** Fails closed: a disk image whose app has another bundle identifier than the installed one is never swapped in. */
    private void requireSameBundleId(Path installed, Path staged) throws IOException {
        var expected = bundleId(installed);
        var actual = bundleId(staged);
        if (expected.isBlank() || !expected.equals(actual)) {
            throw new IOException("The disk image's app has another bundle identifier than the installed one");
        }
    }

    private String bundleId(Path app) throws IOException {
        var result = runner.run(List.of(DEFAULTS, "read", app.resolve(INFO_PLIST).toString(), BUNDLE_ID_KEY), properties.hdiutilDetachTimeout());
        require(result, "read the bundle identifier");
        return result.stdout().strip();
    }

    private static void require(CommandRunner.Result result, String what) throws IOException {
        if (!result.succeeded()) {
            throw new IOException("Could not " + what + (result.timedOut() ? " (timed out)" : " (exit " + result.exitCode() + ")"));
        }
    }
}
