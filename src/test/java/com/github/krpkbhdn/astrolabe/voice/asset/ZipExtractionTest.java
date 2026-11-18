package com.github.krpkbhdn.astrolabe.voice.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipExtractionTest {

    private static final long LIMIT = 1 << 20;
    private static final String ENTRY =
            "build-apple/whisper.xcframework/macos-arm64_x86_64/whisper.framework/Versions/A/whisper";

    @TempDir
    Path dir;

    @Test
    void replacesExistingDirectory() throws IOException {
        var target = dir.resolve("runtime");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "old");

        ZipExtraction.extract(zip("new.txt"), target, ZipExtraction.MAX_ENTRIES, LIMIT);

        assertThat(target.resolve("new.txt")).hasContent("x");
        assertThat(target.resolve("old.txt")).doesNotExist();
        assertThat(dir.resolve("runtime.part")).doesNotExist();
    }

    @Test
    void rejectedArchiveLeavesExistingDirectoryUntouched() throws IOException {
        var target = dir.resolve("runtime");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "old");

        assertThatThrownBy(() -> ZipExtraction.extract(zip("../evil.txt"), target, ZipExtraction.MAX_ENTRIES, LIMIT)).isInstanceOf(IOException.class);

        assertThat(target.resolve("old.txt")).hasContent("old");
        assertThat(dir.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void tooManyEntriesAreRejectedAndTheTargetStaysUntouched() throws IOException {
        var target = dir.resolve("runtime");

        assertThatThrownBy(() -> ZipExtraction.extract(zip("a.txt", "b.txt"), target, 1, 1000)).isInstanceOf(IOException.class).hasMessageContaining("entries");

        assertThat(target).doesNotExist();
        assertThat(dir.resolve("runtime.part")).doesNotExist();
    }

    @Test
    void tooManyUnpackedBytesAreRejected() throws IOException {
        var target = dir.resolve("runtime");

        assertThatThrownBy(() -> ZipExtraction.extract(zip("a.txt", "b.txt"), target, 10, 1)).isInstanceOf(IOException.class).hasMessageContaining("larger");

        assertThat(target).doesNotExist();
    }

    @Test
    void moveFailureIsReportedAfterRetries() {
        assertThatThrownBy(() -> ZipExtraction.moveIntoPlace(dir.resolve("missing"), dir.resolve("target"), 1))
                .isInstanceOf(IOException.class).hasMessageContaining("5 attempts");
    }

    @Test
    void extractEntryWritesOnlyTheNamedEntryByItsPlainName() throws IOException {
        var target = dir.resolve("runtime");

        ZipExtraction.extractEntry(zip("a/other", "a/b/whisper", "../evil"), target, "a/b/whisper", LIMIT);

        assertThat(target.resolve("whisper")).hasContent("x");
        assertThat(target.toFile().list()).containsExactly("whisper");
        assertThat(dir.resolve("evil")).doesNotExist();
    }

    @Test
    void extractEntryRejectsAMissingEntryAndKeepsTheTarget() throws IOException {
        var target = dir.resolve("runtime");
        Files.createDirectories(target);

        assertThatThrownBy(() -> ZipExtraction.extractEntry(zip("a/other"), target, "a/whisper", LIMIT))
                .isInstanceOf(IOException.class).hasMessageContaining("no file");

        assertThat(target).isDirectory();
        assertThat(dir.resolve("runtime.part")).doesNotExist();
    }

    @Test
    void extractEntrySymlinkEntryNeverBecomesALink() throws IOException {
        var link = new ZipArchiveEntry(ENTRY);
        link.setUnixMode(UnixStat.LINK_FLAG | 0777);
        var target = dir.resolve("runtime");

        ZipExtraction.extractEntry(zipOf(link), target, ENTRY, LIMIT);

        assertThat(Files.isSymbolicLink(target.resolve("whisper"))).isFalse();
        assertThat(target.toFile().list()).containsExactly("whisper");
    }

    @Test
    void extractEntryRejectsADirectoryEntry() throws IOException {
        var zip = zipOf(new ZipArchiveEntry(ENTRY + "/"));

        assertThatThrownBy(() -> ZipExtraction.extractEntry(zip, dir.resolve("runtime"), ENTRY, LIMIT))
                .isInstanceOf(IOException.class).hasMessageContaining("no file");
    }

    @Test
    void extractEntryMatchesTheNameExactlyNotBySuffix() throws IOException {
        var zip = zipOf(new ZipArchiveEntry("evil/" + ENTRY), new ZipArchiveEntry("other/whisper"));

        assertThatThrownBy(() -> ZipExtraction.extractEntry(zip, dir.resolve("runtime"), ENTRY, LIMIT))
                .isInstanceOf(IOException.class);
    }

    @Test
    void extractEntryOfANonZipIsAnIoErrorAndLeavesNothing() throws IOException {
        var bad = Files.write(dir.resolve("bad.zip"), new byte[] {1, 2, 3});

        assertThatThrownBy(() -> ZipExtraction.extractEntry(bad, dir.resolve("runtime"), ENTRY, LIMIT))
                .isInstanceOf(IOException.class);

        assertThat(dir.resolve("runtime")).doesNotExist();
        assertThat(dir.resolve("runtime.part")).doesNotExist();
    }

    @Test
    void extractEntryRejectsTooManyUnpackedBytes() throws IOException {
        assertThatThrownBy(() -> ZipExtraction.extractEntry(zip("a/whisper"), dir.resolve("runtime"), "a/whisper", 0))
                .isInstanceOf(IOException.class).hasMessageContaining("larger");
        assertThat(dir.resolve("runtime")).doesNotExist();
    }

    private Path zipOf(ZipArchiveEntry... entries) throws IOException {
        var zip = Files.createTempFile(dir, "a", ".zip");
        try (var out = new ZipArchiveOutputStream(zip)) {
            for (var entry : entries) {
                out.putArchiveEntry(entry);
                if (!entry.isDirectory()) {
                    out.write('x');
                }
                out.closeArchiveEntry();
            }
        }
        return zip;
    }

    private Path zip(String... entryNames) throws IOException {
        var zip = Files.createTempFile(dir, "a", ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var entryName : entryNames) {
                out.putNextEntry(new ZipEntry(entryName));
                out.write('x');
                out.closeEntry();
            }
        }
        return zip;
    }
}
