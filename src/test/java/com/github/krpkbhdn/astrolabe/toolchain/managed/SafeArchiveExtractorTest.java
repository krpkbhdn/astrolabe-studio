package com.github.krpkbhdn.astrolabe.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeArchiveExtractorTest {

    private static final long MANY = 1000;
    private static final long BIG = 1_000_000;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final SafeArchiveExtractor extractor = new SafeArchiveExtractor();

    @TempDir
    Path temp;

    @FunctionalInterface
    private interface TarFiller {
        void fill(TarArchiveOutputStream tar) throws IOException;
    }

    private Path tar(TarFiller filler) throws IOException {
        Path file = temp.resolve("a.tar.gz");
        try (OutputStream out = Files.newOutputStream(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            filler.fill(tar);
        }
        return file;
    }

    private static void tarFile(TarArchiveOutputStream tar, String name, String content, int mode)
            throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        entry.setMode(mode);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static void tarLink(TarArchiveOutputStream tar, String name, byte flag, String target)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, flag);
        entry.setLinkName(target);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private Path zip(String name, int unixMode, String content) throws IOException {
        Path file = temp.resolve("a.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setUnixMode(unixMode);
            zip.putArchiveEntry(entry);
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        return file;
    }

    private Path target() {
        return temp.resolve("out");
    }

    @Test
    void extractsNormalTreeAndStripsSingleTopFolder() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/bin/run", "echo", 0644);
            tarFile(t, "pkg/lib/x.txt", "x", 0644);
        });

        int count = extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(count).isEqualTo(2);
        assertThat(target().resolve("bin/run")).hasContent("echo");
        assertThat(target().resolve("lib/x.txt")).hasContent("x");
    }

    @Test
    void keepsTopFoldersWhenThereAreTwo() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "one/a.txt", "a", 0644);
            tarFile(t, "two/b.txt", "b", 0644);
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(target().resolve("one/a.txt")).exists();
        assertThat(target().resolve("two/b.txt")).exists();
    }

    @Test
    void extractsZip() throws IOException {
        Path archive = zip("top/a.txt", 0100644, "zipped");

        extractor.extract(archive, target(), false, MANY, BIG);

        assertThat(target().resolve("a.txt")).hasContent("zipped");
    }

    @Test
    void rejectsZipSlipInTar() throws IOException {
        Path archive = tar(t -> tarFile(t, "pkg/../../evil.txt", "x", 0644));

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, BIG))
                .isInstanceOf(IOException.class);
        assertThat(temp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsZipSlipInZip() throws IOException {
        Path archive = zip("../evil.txt", 0100644, "x");

        assertThatThrownBy(() -> extractor.extract(archive, target(), false, MANY, BIG))
                .isInstanceOf(IOException.class);
        assertThat(temp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsAbsolutePath() throws IOException {
        Path archive = zip("/tmp/evil.txt", 0100644, "x");

        assertThatThrownBy(() -> extractor.extract(archive, target(), false, MANY, BIG))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDriveLetter() throws IOException {
        Path archive = zip("C:/evil.txt", 0100644, "x");

        assertThatThrownBy(() -> extractor.extract(archive, target(), false, MANY, BIG))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsWindowsReservedDeviceNames() throws IOException {
        for (String name : new String[] {"NUL", "con.txt", "sub/COM1"}) {
            Path archive = zip(name, 0100644, "x");

            assertThatThrownBy(() -> extractor.extract(archive, target(), false, MANY, BIG))
                    .as(name)
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void acceptsNamesThatMerelyStartLikeDeviceNames() throws IOException {
        Path archive = zip("console.txt", 0100644, "x");

        extractor.extract(archive, target(), false, MANY, BIG);

        assertThat(target().resolve("console.txt")).exists();
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/a.txt", "a", 0644);
            tarLink(t, "pkg/bad", TarConstants.LF_SYMLINK, "../../outside");
        });

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, BIG))
                .isInstanceOf(IOException.class).hasMessageContaining("Link");
    }

    @Test
    void rejectsAbsoluteSymlink() throws IOException {
        Path archive = tar(t -> tarLink(t, "pkg/bad", TarConstants.LF_SYMLINK, "/etc/passwd"));

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, BIG))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsHardlinkEscape() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/a.txt", "a", 0644);
            tarLink(t, "pkg/h", TarConstants.LF_LINK, "../../outside");
        });

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, BIG))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDeviceEntry() throws IOException {
        Path archive = tar(t -> {
            TarArchiveEntry device = new TarArchiveEntry("pkg/dev", TarConstants.LF_CHR);
            t.putArchiveEntry(device);
            t.closeArchiveEntry();
        });

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, BIG))
                .isInstanceOf(IOException.class).hasMessageContaining("Unsupported");
    }

    @Test
    void rejectsTooManyEntries() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/1", "1", 0644);
            tarFile(t, "pkg/2", "2", 0644);
            tarFile(t, "pkg/3", "3", 0644);
        });

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, 2, BIG))
                .isInstanceOf(IOException.class).hasMessageContaining("entries");
    }

    @Test
    void capsRealUnpackedBytesNotTheHeader() throws IOException {
        Path archive = zip("pkg/zeros.bin", 0100644, "0".repeat(100_000));

        assertThatThrownBy(() -> extractor.extract(archive, target(), false, MANY, 1000))
                .isInstanceOf(IOException.class).hasMessageContaining("unpacks");
    }

    @Test
    void tarWhoseDeclaredSizesExceedTheCapIsRefusedBeforeAnythingIsWritten() throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/a.bin", "0".repeat(600), 0644);
            tarFile(t, "pkg/b.bin", "0".repeat(600), 0644);
        });

        assertThatThrownBy(() -> extractor.extract(archive, target(), true, MANY, 1000))
                .isInstanceOf(IOException.class).hasMessageContaining("unpacks");
        assertThat(target().resolve("a.bin")).doesNotExist();
    }

    @Test
    void keepsOwnerExecAndDropsSetuidAndWritableBits() throws IOException {
        assumeTrue(POSIX);
        Path archive = tar(t -> {
            tarFile(t, "pkg/tool", "x", 04777);
            tarFile(t, "pkg/data", "y", 0666);
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        Set<PosixFilePermission> tool = Files.getPosixFilePermissions(target().resolve("tool"));
        assertThat(tool).contains(PosixFilePermission.OWNER_EXECUTE)
                .doesNotContain(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE);
        assertThat(Files.getPosixFilePermissions(target().resolve("data")))
                .doesNotContain(PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void createsSymlinkInsideRootOnPosix() throws IOException {
        assumeTrue(POSIX);
        Path archive = tar(t -> {
            tarFile(t, "pkg/lib/real.so", "so", 0644);
            tarLink(t, "pkg/lib/link.so", TarConstants.LF_SYMLINK, "real.so");
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(Files.isSymbolicLink(target().resolve("lib/link.so"))).isTrue();
        assertThat(target().resolve("lib/link.so")).hasContent("so");
    }

    private static void tarDirectory(TarArchiveOutputStream tar, String name, int mode) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
        entry.setMode(mode);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    @Test
    void archiveWithoutTopFolderIsNotStrippedAndSetgidDirectoriesAreExtracted() throws IOException {
        Path archive = tar(t -> {
            tarDirectory(t, "bin", 02755);
            tarDirectory(t, "plugins", 02755);
            tarFile(t, "bin/jdtls", "sh", 0755);
            tarFile(t, "plugins/launcher.jar", "jar", 0644);
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(target().resolve("bin/jdtls")).hasContent("sh");
        assertThat(target().resolve("plugins/launcher.jar")).hasContent("jar");
        if (POSIX) {
            assertThat(Files.getPosixFilePermissions(target().resolve("bin")))
                    .doesNotContain(PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_WRITE);
        }
    }

    @Test
    void singleTopFolderOfAKotlinStyleArchiveIsStrippedAndNestedRelativeSymlinksWork() throws IOException {
        Path archive = tar(t -> {
            tarDirectory(t, "kotlin-server-1.0", 0755);
            tarFile(t, "kotlin-server-1.0/bin/intellij-server", "x", 0755);
            tarFile(t, "kotlin-server-1.0/jbr/legal/java.base/LICENSE", "lic", 0644);
            tarLink(t, "kotlin-server-1.0/jbr/legal/jdk.x/LICENSE", TarConstants.LF_SYMLINK, "../java.base/LICENSE");
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(target().resolve("bin/intellij-server")).hasContent("x");
        if (POSIX) {
            assertThat(Files.isSymbolicLink(target().resolve("jbr/legal/jdk.x/LICENSE"))).isTrue();
            assertThat(target().resolve("jbr/legal/jdk.x/LICENSE")).hasContent("lic");
        }
    }

    @Test
    void copiesHardlinkInsideRoot()throws IOException {
        Path archive = tar(t -> {
            tarFile(t, "pkg/a.txt", "same", 0644);
            tarLink(t, "pkg/b.txt", TarConstants.LF_LINK, "pkg/a.txt");
        });

        extractor.extract(archive, target(), true, MANY, BIG);

        assertThat(target().resolve("b.txt")).hasContent("same");
    }
}
