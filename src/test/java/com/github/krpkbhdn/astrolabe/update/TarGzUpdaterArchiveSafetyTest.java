package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hostile tar.gz layouts against {@link TarGzUpdater#prepare}: nothing may be written outside the private folder, and a refused archive leaves nothing behind. */
class TarGzUpdaterArchiveSafetyTest {

    private static final String LAUNCHER = "Astrolabe Studio/bin/Astrolabe Studio";

    @TempDir
    Path temp;

    private interface Entries {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private Path archive(Entries entries) throws IOException {
        var updates = Files.createDirectories(temp.resolve("updates"));
        var file = updates.resolve("release.tar.gz");
        try (OutputStream out = Files.newOutputStream(file); var tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            entries.write(tar);
        }
        return file;
    }

    private static void file(TarArchiveOutputStream tar, String name) throws IOException {
        var bytes = "x".getBytes(StandardCharsets.UTF_8);
        var entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        entry.setMode(0755);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    private static void validLayout(TarArchiveOutputStream tar) throws IOException {
        file(tar, LAUNCHER);
        file(tar, "install.sh");
        file(tar, "icon.png");
    }

    private static void link(TarArchiveOutputStream tar, String name, byte type, String target) throws IOException {
        var entry = new TarArchiveEntry(name, type);
        entry.setLinkName(target);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private void assertRefused(Entries entries) throws IOException {
        var archive = archive(entries);

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), new Installation(InstallKind.TAR_GZ, "x64", temp, temp.resolve("x"))))
                .isInstanceOf(IOException.class);
        try (var children = Files.list(temp.resolve("updates"))) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("release.tar.gz");
        }
        try (var siblings = Files.walk(temp)) {
            assertThat(siblings.map(path -> path.getFileName().toString())).doesNotContain("evil.txt", "evil");
        }
    }

    @Test
    void refusesTraversalInsideTheTopFolder() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            file(tar, "Astrolabe Studio/../../evil.txt");
        });
    }

    @Test
    void refusesBackslashTraversal() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            file(tar, "..\\evil.txt");
        });
    }

    @Test
    void refusesAnAbsoluteSymlinkTarget() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/lib/evil", TarArchiveEntry.LF_SYMLINK, "/etc");
        });
    }

    @Test
    void refusesASymlinkWithDotDotInTheMiddleOfItsTarget() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/lib/evil", TarArchiveEntry.LF_SYMLINK, "lib/../../../outside");
        });
    }

    @Test
    void refusesASymlinkClimbingOutOfTheFolderByLeadingDotDots() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/a/b/evil", TarArchiveEntry.LF_SYMLINK, "../../../../outside");
        });
    }

    @Test
    void refusesToWriteAFileThroughAnEarlierSymlinkToTheOutside() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/evil", TarArchiveEntry.LF_SYMLINK, "../../outside");
            file(tar, "Astrolabe Studio/evil/evil.txt");
        });
    }

    @Test
    void refusesAHardlinkToAFileOutsideTheFolder() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/evil", TarArchiveEntry.LF_LINK, "../../outside");
        });
    }

    @Test
    void refusesAnAbsoluteHardlinkTarget() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            link(tar, "Astrolabe Studio/evil", TarArchiveEntry.LF_LINK, "/etc/passwd");
        });
    }

    @Test
    void refusesDeviceAndFifoEntries() throws IOException {
        assertRefused(tar -> {
            validLayout(tar);
            tar.putArchiveEntry(new TarArchiveEntry("Astrolabe Studio/dev", TarArchiveEntry.LF_CHR));
            tar.closeArchiveEntry();
        });
        assertRefused(tar -> {
            validLayout(tar);
            tar.putArchiveEntry(new TarArchiveEntry("Astrolabe Studio/pipe", TarArchiveEntry.LF_FIFO));
            tar.closeArchiveEntry();
        });
    }

    @Test
    void refusesALauncherThatIsADirectoryInsteadOfAFile() throws IOException {
        assertRefused(tar -> {
            file(tar, "install.sh");
            file(tar, "icon.png");
            tar.putArchiveEntry(new TarArchiveEntry(LAUNCHER + "/"));
            tar.closeArchiveEntry();
        });
    }

    @Test
    void refusesAnArchiveThatIsNotGzip() throws IOException {
        var updates = Files.createDirectories(temp.resolve("updates"));
        var bogus = Files.writeString(updates.resolve("release.tar.gz"), "not an archive");

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(bogus, UpdateFiles.sha256(bogus), new Installation(InstallKind.TAR_GZ, "x64", temp, temp.resolve("x")))).isInstanceOf(IOException.class);
        try (var children = Files.list(updates)) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("release.tar.gz");
        }
    }

    @Test
    void anArchiveWithoutATopFolderIsNotAcceptedAsTheAppLayout() throws IOException {
        assertRefused(tar -> {
            file(tar, "bin/Astrolabe Studio");
            file(tar, "install.sh");
            file(tar, "icon.png");
        });
    }
}
