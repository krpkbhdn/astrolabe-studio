package com.github.krpkbhdn.ccmanager.toolchain.managed;

import com.github.krpkbhdn.ccmanager.toolchain.DownloadSource;
import com.github.krpkbhdn.ccmanager.toolchain.LatestSource;
import com.github.krpkbhdn.ccmanager.toolchain.LauncherSpec;
import com.github.krpkbhdn.ccmanager.toolchain.ArchiveKind;
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition;
import com.github.krpkbhdn.ccmanager.toolchain.ToolchainProperties;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Downloads, verifies and unpacks a tool into {@code tools/<id>/<version>} and writes its launcher. The new version becomes visible
 * only by an atomic rename of a fully unpacked folder, so a failed or cancelled install leaves the old version untouched.
 */
public final class ManagedToolInstaller {

    /** @param upToDate the newest version was already installed; only its launcher was rewritten */
    public record Installed(String version, boolean upToDate, Path launcher) {}

    private static final String CHECKSUM_SUFFIX = ".sha256";
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final double MEGABYTE = 1024.0 * 1024.0;

    private final ManagedToolsDirectory directory;
    private final LauncherWriter launchers;
    private final ToolchainProperties properties;
    private final AllowlistedHttp http;
    private final LauncherWriter.Platform platform;
    private final SafeArchiveExtractor extractor = new SafeArchiveExtractor();

    public ManagedToolInstaller(ManagedToolsDirectory directory, LauncherWriter launchers, ToolchainProperties properties,
                                AllowlistedHttp http, LauncherWriter.Platform platform) {
        this.directory = directory;
        this.launchers = launchers;
        this.properties = properties;
        this.http = http;
        this.platform = platform;
    }

    /** @param onOutput gets the whole progress text so far, the download percentage line replaced in place */
    public Installed install(ToolDefinition tool, DownloadSource source, Consumer<String> onOutput)
            throws IOException, InterruptedException {
        var log = new Progress(onOutput);
        directory.sweepStale(tool.id());
        log.line("Looking up the latest version of " + tool.name() + "...");
        var version = latestVersion(source.latest());
        var target = directory.versionDirectory(tool.id(), version);
        log.line("Latest version: " + version);
        if (Files.exists(target.resolve(ManagedToolsDirectory.COMPLETE_MARKER))) {
            if (launcherTargetExists(source.launcher(), target)) {
                var launcher = writeLauncher(tool, source.launcher(), target);
                log.line("Already up to date. Launcher: " + launcher);
                return new Installed(version, true, launcher);
            }
            log.line("The installed files are incomplete, installing again");
        }
        var archiveUri = URI.create(source.archiveUrl().replace("{version}", version));
        var expectedChecksum = checksumText(URI.create(archiveUri + CHECKSUM_SUFFIX));
        var partial = target.resolveSibling(version + ".partial-" + UUID.randomUUID());
        try {
            unpack(source.archive(), archiveUri, expectedChecksum, partial, log);
            publish(partial, target, version);
        } finally {
            ManagedToolsDirectory.deleteTree(partial);
        }
        var launcher = publishLauncher(tool, source.launcher(), target);
        directory.markComplete(target);
        log.line("Launcher: " + launcher);
        directory.deleteOtherVersions(tool.id(), version);
        return new Installed(version, false, launcher);
    }

    /** The version only counts as installed once its launcher is written; a failed write drops the new folder and keeps the old version. */
    private Path publishLauncher(ToolDefinition tool, LauncherSpec spec, Path target) throws IOException {
        try {
            return writeLauncher(tool, spec, target);
        } catch (IOException e) {
            ManagedToolsDirectory.deleteTree(target);
            if (e instanceof FileSystemException && !(e instanceof NoSuchFileException)) {
                throw new IOException("Close sessions that use " + tool.name() + " and retry", e);
            }
            throw e;
        }
    }

    private static boolean launcherTargetExists(LauncherSpec spec, Path install) {
        return switch (spec.kind()) {
            case JDTLS -> LauncherWriter.hasJdtlsLauncherJar(install);
            case BINARY -> {
                var binary = install.resolve(spec.path()).normalize();
                yield binary.startsWith(install) && Files.isRegularFile(binary);
            }
        };
    }

    private String latestVersion(LatestSource latest) throws IOException, InterruptedException {
        return new LatestVersionResolver(http, properties.metadataTimeout()).resolve(URI.create(latest.url()),
                Pattern.compile(latest.pattern()), latest.kind() == LatestSource.Kind.REDIRECT);
    }

    private String checksumText(URI uri) throws IOException, InterruptedException {
        var text = http.readText(uri, MAX_CHECKSUM_BYTES, properties.metadataTimeout());
        ChecksumFile.expected(text);
        return text;
    }

    private void unpack(ArchiveKind kind, URI archiveUri, String expectedChecksum, Path partial, Progress log)
            throws IOException, InterruptedException {
        var downloader = new ArchiveDownload(http, properties.downloadTimeout(), properties.maxDownloadSize().toBytes());
        var downloaded = downloader.download(archiveUri, directory.downloadsDirectory(), log::download);
        try {
            log.endDownload();
            ChecksumFile.verify(expectedChecksum, downloaded.sha256());
            log.line("Checksum verified (SHA-256)");
            var count = extractor.extract(downloaded.file(), partial, kind == ArchiveKind.TAR_GZ,
                    properties.maxArchiveEntries(), properties.maxUnpackedSize().toBytes());
            log.line("Extracted " + count + " files");
        } catch (InterruptedIOException | ClosedByInterruptException e) {
            Thread.interrupted();
            throw (InterruptedException) new InterruptedException("Install cancelled").initCause(e);
        } finally {
            Files.deleteIfExists(downloaded.file());
        }
    }

    /** A folder left without the marker by a crash is moved aside, where the next cleanup removes it. */
    private static void publish(Path partial, Path target, String version) throws IOException {
        if (Files.exists(target)) {
            Files.move(target, target.resolveSibling(version + ".partial-stale-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path writeLauncher(ToolDefinition tool, LauncherSpec spec, Path install) throws IOException {
        Files.createDirectories(directory.binDirectory());
        return switch (spec.kind()) {
            case JDTLS -> launchers.writeJdtls(tool.binary(), install, directory.stateDirectory(tool.id()), platform);
            case BINARY -> {
                var binary = install.resolve(spec.path()).normalize();
                if (!binary.startsWith(install) || !Files.isRegularFile(binary)) {
                    throw new IOException("The archive has no " + spec.path());
                }
                var state = directory.stateDirectory(tool.id());
                Files.createDirectories(state);
                yield launchers.writeBinary(tool.binary(), binary, spec.argumentsFor(state), platform);
            }
        };
    }

    /** Rendered progress text: finished lines plus one percentage line that is replaced in place. */
    private static final class Progress {

        private final Consumer<String> sink;
        private final StringBuilder lines = new StringBuilder();
        private String percentLine = "";

        Progress(Consumer<String> sink) {
            this.sink = sink;
        }

        void line(String text) {
            lines.append(text).append('\n');
            emit();
        }

        void download(int percent, long read, long total) {
            percentLine = total > 0
                    ? "Downloading " + percent + "% (" + megabytes(read) + " of " + megabytes(total) + " MB)"
                    : "Downloading " + megabytes(read) + " MB";
            emit();
        }

        void endDownload() {
            if (!percentLine.isEmpty()) {
                lines.append(percentLine).append('\n');
                percentLine = "";
                emit();
            }
        }

        private void emit() {
            sink.accept(lines + percentLine);
        }

        private static String megabytes(long bytes) {
            return String.format(java.util.Locale.ROOT, "%.1f", bytes / MEGABYTE);
        }
    }
}
