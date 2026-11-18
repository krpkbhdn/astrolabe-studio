package com.github.krpkbhdn.astrolabe.toolchain.managed;

import com.github.krpkbhdn.astrolabe.toolchain.DownloadSource;
import com.github.krpkbhdn.astrolabe.toolchain.LatestSource;
import com.github.krpkbhdn.astrolabe.toolchain.LauncherSpec;
import com.github.krpkbhdn.astrolabe.toolchain.ArchiveKind;
import com.github.krpkbhdn.astrolabe.toolchain.PinnedVersion;
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition;
import com.github.krpkbhdn.astrolabe.toolchain.ToolchainProperties;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads, verifies and unpacks a tool into {@code tools/<id>/<version>} and writes its launcher. The new version becomes visible
 * only by an atomic rename of a fully unpacked folder, so a failed or cancelled install leaves the old version untouched.
 */
public final class ManagedToolInstaller {

    /** @param upToDate the newest version was already installed; only its launcher was rewritten */
    public record Installed(String version, boolean upToDate, Path launcher) {}

    private static final Logger log = LoggerFactory.getLogger(ManagedToolInstaller.class);
    private static final String CHANNEL_FOLDER = ".astrolabe";
    private static final String CHANNEL_FILE = "channel";
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

    /** @param onOutput gets the whole progress text so far, the download percentage line replaced in place; a tool without a pinned source ignores the channel */
    public Installed install(ToolDefinition tool, DownloadSource source, ToolChannel channel, Consumer<String> onOutput)
            throws IOException, InterruptedException {
        return install(tool, source, channel, onOutput, false);
    }

    /** Fetches and verifies a fresh copy even when the newest version is already installed; the old copy is replaced only after that, and restored if the swap fails. */
    public Installed reinstall(ToolDefinition tool, DownloadSource source, ToolChannel channel, Consumer<String> onOutput)
            throws IOException, InterruptedException {
        return install(tool, source, channel, onOutput, true);
    }

    private Installed install(ToolDefinition tool, DownloadSource source, ToolChannel channel, Consumer<String> onOutput,
                              boolean repair) throws IOException, InterruptedException {
        var log = new Progress(onOutput);
        directory.sweepStale(tool.id());
        var pinned = channel == ToolChannel.VERIFIED ? source.pinnedVersion() : Optional.<PinnedVersion>empty();
        String version;
        if (pinned.isPresent()) {
            version = pinned.get().version();
            log.line("Verified version of " + tool.name() + ": " + version);
        } else {
            log.line("Looking up the latest version of " + tool.name() + "...");
            version = latestVersion(source.latest());
            log.line("Latest version: " + version);
        }
        var target = directory.versionDirectory(tool.id(), version);
        if (!repair && Files.exists(target.resolve(ManagedToolsDirectory.COMPLETE_MARKER))) {
            if (launcherTargetExists(usable(source.launcher(), target), target)) {
                writeChannel(target, channel);
                var launcher = writeLauncher(tool, source.launcher(), target);
                log.line("Already up to date. Launcher: " + launcher);
                return new Installed(version, true, launcher);
            }
            log.line("The installed files are incomplete, installing again");
        }
        var archiveUri = URI.create(pinned.isPresent() ? pinned.get().url() : source.archiveUrl().replace("{version}", version));
        var expectedChecksum = pinned.isPresent() ? pinned.get().sha256() : checksumText(URI.create(archiveUri + CHECKSUM_SUFFIX));
        var partial = target.resolveSibling(version + ".partial-" + UUID.randomUUID());
        Path replaced;
        try {
            unpack(source.archive(), archiveUri, expectedChecksum, partial, log);
            writeChannel(partial, channel);
            replaced = publish(partial, target);
        } finally {
            ManagedToolsDirectory.deleteTree(partial);
        }
        Path launcher;
        try {
            launcher = publishLauncher(tool, source.launcher(), target);
        } catch (IOException e) {
            restore(tool, source.launcher(), replaced, target);
            throw e;
        }
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

    /** The spec itself, or its fallback when the unpacked version lacks the spec's file. */
    private static LauncherSpec usable(LauncherSpec spec, Path install) {
        return spec.fallback() != null && !launcherTargetExists(spec, install) && launcherTargetExists(spec.fallback(), install)
                ? spec.fallback() : spec;
    }

    /**
     * Rewrites the launcher of the newest installed version without any download, so a launcher from an older app version gets the current form.
     * A VERIFIED install that is not the pinned version, or one whose launcher cannot be written, gets a launcher that only explains why it does not run.
     */
    public void refreshLauncher(ToolDefinition tool, DownloadSource source) throws IOException {
        var version = directory.installedVersion(tool.id());
        if (version.isEmpty()) {
            return;
        }
        var install = directory.versionDirectory(tool.id(), version.get());
        var pinned = source.pinnedVersion();
        if (pinned.isPresent() && installedChannel(install) == ToolChannel.VERIFIED && !pinned.get().version().equals(version.get())) {
            writeRefusal(tool, tool.name() + " " + version.get() + " is not the verified version " + pinned.get().version()
                    + ". Update installs it");
            return;
        }
        try {
            writeLauncher(tool, source.launcher(), install);
        } catch (IOException e) {
            log.warn("Could not write the launcher of {}: {}", tool.id(), e.getClass().getSimpleName());
            writeRefusal(tool, "The launcher of " + tool.name() + " could not be written. Reinstall it");
        }
    }

    private void writeRefusal(ToolDefinition tool, String reason) throws IOException {
        Files.createDirectories(directory.binDirectory());
        launchers.writeRefusal(tool.binary(), reason, platform);
    }

    /** The channel of the newest installed version; VERIFIED when nothing is installed or the install predates channels. */
    public ToolChannel installedChannel(ToolDefinition tool) {
        return directory.installedVersion(tool.id())
                .map(version -> installedChannel(directory.versionDirectory(tool.id(), version))).orElse(ToolChannel.VERIFIED);
    }

    private String latestVersion(LatestSource latest) throws IOException, InterruptedException {
        return new LatestVersionResolver(http, properties.metadataTimeout()).resolve(URI.create(latest.url()),
                Pattern.compile(latest.pattern()), latest.kind() == LatestSource.Kind.REDIRECT);
    }

    private static void writeChannel(Path install, ToolChannel channel) throws IOException {
        var folder = install.resolve(CHANNEL_FOLDER);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(CHANNEL_FILE), channel.name());
    }

    /** The channel an install was made from; a missing or unreadable file (legacy install) is VERIFIED. */
    static ToolChannel installedChannel(Path install) {
        try {
            return ToolChannel.valueOf(Files.readString(install.resolve(CHANNEL_FOLDER).resolve(CHANNEL_FILE)).strip());
        } catch (IOException | IllegalArgumentException e) {
            return ToolChannel.VERIFIED;
        }
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

    /** The folder already at the target is moved aside, not deleted, so a failed swap can bring it back; the next cleanup removes it. */
    private static Path publish(Path partial, Path target) throws IOException {
        Path aside = null;
        if (Files.exists(target)) {
            aside = target.resolveSibling(target.getFileName() + ".partial-stale-" + UUID.randomUUID());
            Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (aside != null) {
                Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            }
            throw e;
        }
        return aside;
    }

    /** Puts a complete folder that was moved aside back and rewrites its launcher; best effort, the install error is what gets reported. */
    private void restore(ToolDefinition tool, LauncherSpec spec, Path aside, Path target) {
        if (aside == null || !Files.exists(aside.resolve(ManagedToolsDirectory.COMPLETE_MARKER))) {
            return;
        }
        try {
            ManagedToolsDirectory.deleteTree(target);
            Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            writeLauncher(tool, spec, target);
        } catch (IOException e) {
            // the old copy stays in its folder; only its launcher may be stale
        }
    }

    private Path writeLauncher(ToolDefinition tool, LauncherSpec declared, Path install) throws IOException {
        Files.createDirectories(directory.binDirectory());
        var spec = usable(declared, install);
        return switch (spec.kind()) {
            case JDTLS -> launchers.writeJdtls(tool.binary(), install, directory.stateDirectory(tool.id()), platform);
            case BINARY -> {
                var binary = install.resolve(spec.path()).normalize();
                if (!binary.startsWith(install) || !Files.isRegularFile(binary)) {
                    throw new IOException("The archive has no " + spec.path());
                }
                var state = directory.stateDirectory(tool.id());
                Files.createDirectories(state);
                yield launchers.writeBinary(tool.binary(), binary, spec.argumentsFor(state, install), !spec.routed(), platform);
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
