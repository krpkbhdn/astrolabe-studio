package com.github.krpkbhdn.astrolabe.overlay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    @TempDir
    Path dir;

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void writesTheContentToANewFile() throws IOException {
        var file = dir.resolve("token");

        OwnerOnlyFiles.write(file, "secret");

        assertThat(file).hasContent("secret");
    }

    @Test
    void overwritingAnExistingFileReplacesItsContent() throws IOException {
        var file = dir.resolve("token");
        Files.writeString(file, "old and much longer content");

        OwnerOnlyFiles.write(file, "new");

        assertThat(file).hasContent("new");
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        OwnerOnlyFiles.write(dir.resolve("token"), "secret");

        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("token");
        }
    }

    @Test
    void writeFailsWhenTheParentDirectoryIsMissing() {
        var file = dir.resolve("missing").resolve("token");

        assertThatThrownBy(() -> OwnerOnlyFiles.write(file, "secret")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void writtenFileIsReadableOnlyByTheOwnerOnPosix() throws IOException {
        assumeTrue(posix(dir));
        var file = dir.resolve("token");

        OwnerOnlyFiles.write(file, "secret");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void overwrittenFileKeepsOwnerOnlyPermissionsOnPosix() throws IOException {
        assumeTrue(posix(dir));
        var file = dir.resolve("token");
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        OwnerOnlyFiles.write(file, "new");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void createDirectoryCreatesMissingParents() throws IOException {
        var leaf = dir.resolve("a").resolve("b").resolve("c");

        OwnerOnlyFiles.createDirectory(leaf);

        assertThat(leaf).isDirectory();
    }

    @Test
    void createDirectoryOnAnExistingDirectoryKeepsItsContent() throws IOException {
        var existing = Files.createDirectory(dir.resolve("x"));
        var child = Files.writeString(existing.resolve("f"), "kept");

        OwnerOnlyFiles.createDirectory(existing);

        assertThat(child).hasContent("kept");
    }

    @Test
    void createdDirectoriesAreOwnerOnlyOnPosix() throws IOException {
        assumeTrue(posix(dir));
        var leaf = dir.resolve("a").resolve("b");

        OwnerOnlyFiles.createDirectory(leaf);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(leaf))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(leaf.getParent()))).isEqualTo("rwx------");
    }
}
