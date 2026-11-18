package com.github.krpkbhdn.astrolabe.voice.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarGzExtractionTest {

    private static final long LIMIT = 1 << 20;

    @TempDir
    Path dir;

    @Test
    void unpacksOnlyRuntimeLibrariesFlat() throws IOException {
        var target = dir.resolve("runtime");
        var archive = tarGz(new Item("top/libggml-base.so.0.18.1", "x"), new Item("top/libggml.so.0.18.1", "x"),
                new Item("top/libwhisper.so.1.9.2", "x"), new Item("top/libggml-cpu-haswell.so", "x"),
                new Item("top/whisper-cli", "x"), new Item("top/libwhisper.so.1", TarArchiveEntry.LF_SYMLINK, "target"));

        TarGzExtraction.extract(archive, target, LIMIT);

        assertThat(target.toFile().list()).containsExactlyInAnyOrder("libggml-base.so.0.18.1", "libggml.so.0.18.1",
                "libwhisper.so.1.9.2", "libggml-cpu-haswell.so");
        assertThat(dir.resolve("runtime.part")).doesNotExist();
    }

    @Test
    void absolutePathAndDotDotEntriesLandFlatInsideTheTarget() throws IOException {
        var target = dir.resolve("out/runtime");
        var archive = tarGz(new Item("/etc/libggml.so.0.18.1", "a"), new Item("../../../libwhisper.so.1.9.2", "b"));

        TarGzExtraction.extract(archive, target, LIMIT);

        assertThat(target.toFile().list()).containsExactlyInAnyOrder("libggml.so.0.18.1", "libwhisper.so.1.9.2");
        assertThat(dir.resolve("libwhisper.so.1.9.2")).doesNotExist();
        assertThat(dir.resolve("out/libwhisper.so.1.9.2")).doesNotExist();
    }

    @Test
    void executablesAndDirectoriesNamedLikeLibrariesAreSkipped() throws IOException {
        var archive = tarGz(new Item("top/libwhisper.so.1.9.2/", null), new Item("top/whisper-cli", "x"),
                new Item("top/libggml.so", "x"), new Item("top/libggml-base.so.0.18.1.bak", "x"),
                new Item("top/libggml.so.0.18.1", "ok"));

        TarGzExtraction.extract(archive, dir.resolve("r"), LIMIT);

        assertThat(dir.resolve("r").toFile().list()).containsExactly("libggml.so.0.18.1");
    }

    @Test
    void symlinkAndHardLinkWithRuntimeNamesAreNeverCreatedButRegularSiblingsAre() throws IOException {
        var archive = tarGz(new Item("top/libggml.so.0.18.1", "ok"),
                new Item("top/libwhisper.so.1.9.2", TarArchiveEntry.LF_SYMLINK, "/etc/passwd"),
                new Item("top/libggml-cpu-x.so", TarArchiveEntry.LF_LINK, "top/libggml.so.0.18.1"));

        TarGzExtraction.extract(archive, dir.resolve("r"), LIMIT);

        assertThat(dir.resolve("r").toFile().list()).containsExactly("libggml.so.0.18.1");
        assertThat(Files.isSymbolicLink(dir.resolve("r/libggml.so.0.18.1"))).isFalse();
    }

    @Test
    void linksOnlyArchiveIsRejected() throws IOException {
        var archive = tarGz(new Item("top/libwhisper.so.1.9.2", TarArchiveEntry.LF_SYMLINK, "target"),
                new Item("top/libggml.so.0.18.1", TarArchiveEntry.LF_LINK, "target"));

        assertThatThrownBy(() -> TarGzExtraction.extract(archive, dir.resolve("runtime"), LIMIT))
                .isInstanceOf(IOException.class).hasMessageContaining("no whisper runtime library");
        assertThat(dir.resolve("runtime")).doesNotExist();
    }

    @Test
    void duplicateEntriesKeepOneRegularFileWithTheLastContent() throws IOException {
        TarGzExtraction.extract(tarGz(new Item("a/libggml.so.0.1", "first"), new Item("b/libggml.so.0.1", "second")),
                dir.resolve("r"), LIMIT);

        assertThat(dir.resolve("r").toFile().list()).containsExactly("libggml.so.0.1");
        assertThat(dir.resolve("r/libggml.so.0.1")).hasContent("second");
    }

    @Test
    void failedArchiveLeavesExistingDirectoryUntouched() throws IOException {
        var target = dir.resolve("runtime");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "old");

        assertThatThrownBy(() -> TarGzExtraction.extract(tarGz(new Item("top/whisper-cli", "x")), target, LIMIT))
                .isInstanceOf(IOException.class);

        assertThat(target.resolve("old.txt")).hasContent("old");
    }

    @Test
    void tooManyUnpackedBytesAreRejected() throws IOException {
        var archive = tarGz(new Item("top/libggml.so.0.18.1", "0123456789"));

        assertThatThrownBy(() -> TarGzExtraction.extract(archive, dir.resolve("r"), 5))
                .isInstanceOf(IOException.class).hasMessageContaining("larger");
        assertThat(dir.resolve("r")).doesNotExist();
    }

    @Test
    void garbageInputIsAnIoErrorAndLeavesNoPartial() throws IOException {
        var bad = Files.write(dir.resolve("bad.tar.gz"), new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> TarGzExtraction.extract(bad, dir.resolve("r"), LIMIT)).isInstanceOf(IOException.class);

        assertThat(dir.resolve("r")).doesNotExist();
        assertThat(dir.resolve("r.part")).doesNotExist();
    }

    private record Item(String name, byte flag, String content) {
        Item(String name, String content) {
            this(name, TarArchiveEntry.LF_NORMAL, content);
        }
    }

    private Path tarGz(Item... items) throws IOException {
        var archive = Files.createTempFile(dir, "a", ".tar.gz");
        try (var out = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (var item : items) {
                var entry = item.name().endsWith("/") ? new TarArchiveEntry(item.name())
                        : new TarArchiveEntry(item.name(), item.flag());
                var bytes = item.flag() == TarArchiveEntry.LF_NORMAL && item.content() != null
                        ? item.content().getBytes(StandardCharsets.UTF_8) : new byte[0];
                if (item.flag() == TarArchiveEntry.LF_SYMLINK || item.flag() == TarArchiveEntry.LF_LINK) {
                    entry.setLinkName(item.content());
                } else {
                    entry.setSize(bytes.length);
                }
                out.putArchiveEntry(entry);
                out.write(bytes);
                out.closeArchiveEntry();
            }
        }
        return archive;
    }
}
