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

class TarGzUpdaterTest {

    private static final String LAUNCHER = "Astrolabe Studio/bin/Astrolabe Studio";

    @TempDir
    Path temp;

    private Installation installation(InstallKind kind) {
        return new Installation(kind, "x64", temp.resolve("opt"), temp.resolve("opt/bin/Astrolabe Studio"));
    }

    private interface Entries {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private Path archive(Entries entries) throws IOException {
        var file = temp.resolve("release.tar.gz");
        try (OutputStream out = Files.newOutputStream(file); var tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
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

    @Test
    void prepareExtractsAValidArchiveIntoAFreshFolderNextToIt() throws IOException {
        var archive = archive(TarGzUpdaterTest::validLayout);
        var prepared = new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), installation(InstallKind.TAR_GZ));

        assertThat(prepared.getParent()).isEqualTo(temp);
        assertThat(prepared.resolve(LAUNCHER)).isRegularFile();
        assertThat(prepared.resolve("install.sh")).isRegularFile();
        assertThat(prepared.resolve("icon.png")).isRegularFile();
    }

    @Test
    void prepareRejectsAnArchiveWithoutTheLauncherAndRemovesWhatItUnpacked() throws IOException {
        var archive = archive(tar -> {
            file(tar, "install.sh");
            file(tar, "icon.png");
        });

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), installation(InstallKind.TAR_GZ))).isInstanceOf(IOException.class).hasMessageContaining("launcher");
        try (var children = Files.list(temp)) {
            assertThat(children.filter(Files::isDirectory)).isEmpty();
        }
    }

    @Test
    void prepareRejectsAnArchiveThatDiffersFromTheVerifiedHashBeforeUnpackingIt() throws IOException {
        var archive = archive(TarGzUpdaterTest::validLayout);

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, "ab".repeat(32), installation(InstallKind.TAR_GZ)))
                .isInstanceOf(IOException.class).hasMessageContaining("differs");
        try (var children = Files.list(temp)) {
            assertThat(children.filter(Files::isDirectory)).isEmpty();
        }
    }

    @Test
    void prepareRejectsAnArchiveWithoutTheInstallScript() throws IOException {
        var archive = archive(tar -> {
            file(tar, LAUNCHER);
            file(tar, "icon.png");
        });

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), installation(InstallKind.TAR_GZ))).isInstanceOf(IOException.class).hasMessageContaining("install.sh");
    }

    @Test
    void prepareRejectsPathTraversal() throws IOException {
        var archive = archive(tar -> {
            validLayout(tar);
            file(tar, "../evil.txt");
        });

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), installation(InstallKind.TAR_GZ))).isInstanceOf(IOException.class);
        assertThat(temp.resolveSibling("evil.txt")).doesNotExist();
    }

    @Test
    void prepareRejectsASymlinkLeavingTheFolder() throws IOException {
        var archive = archive(tar -> {
            validLayout(tar);
            var link = new TarArchiveEntry("Astrolabe Studio/escape", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("../../outside");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        });

        assertThatThrownBy(() -> new TarGzUpdater(temp, TestUpdateProperties.defaults()).prepare(archive, UpdateFiles.sha256(archive), installation(InstallKind.TAR_GZ))).isInstanceOf(IOException.class);
    }

    @Test
    void supportedNeedsTheTarGzKindAndAWritableInstallParent() throws IOException {
        var home = Files.createDirectories(temp.resolve("home"));
        var updater = new TarGzUpdater(home, TestUpdateProperties.defaults());

        assertThat(updater.supported(installation(InstallKind.TAR_GZ))).isTrue();
        assertThat(updater.supported(installation(InstallKind.MSI))).isFalse();
        assertThat(new TarGzUpdater(temp.resolve("missing").resolve("home"), TestUpdateProperties.defaults()).supported(installation(InstallKind.TAR_GZ))).isTrue();
    }

    @Test
    void theFragmentRunsThePerUserInstallScriptFromAPositionalArgument() {
        assertThat(TarGzUpdater.FRAGMENT).isEqualTo("step install sh \"$1/install.sh\"").doesNotContain("--system");
        assertThat(new TarGzUpdater(temp, TestUpdateProperties.defaults()).needsAuthentication()).isFalse();
        assertThat(new TarGzUpdater(temp, TestUpdateProperties.defaults()).kind()).isEqualTo(InstallKind.TAR_GZ);
    }
}
