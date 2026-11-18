package com.github.krpkbhdn.astrolabe.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsShortPathTest {

    @TempDir
    Path temp;

    @Test
    void anExistingFolderWithSpacesGetsASpaceFreeNameForTheSameFolder() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        var folder = Files.createDirectory(temp.resolve("my long folder name"));

        var shortName = WindowsShortPath.of(folder.toString());

        assumeTrue(!shortName.equals(folder.toString()), "8.3 names are disabled on this volume");
        assertThat(shortName).doesNotContain(" ");
        assertThat(Files.isSameFile(Path.of(shortName), folder)).isTrue();
    }

    @Test
    void aMissingPathComesBackUnchanged() {
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));

        assertThat(WindowsShortPath.of("C:\\no such folder\\anywhere")).isEqualTo("C:\\no such folder\\anywhere");
    }
}
