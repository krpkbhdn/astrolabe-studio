package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedToolsDirectoryTest {

    @TempDir
    Path dataDir;

    ManagedToolsDirectory tools;

    @BeforeEach
    void setUp() {
        tools = new ManagedToolsDirectory(new AppProperties(dataDir));
    }

    private Path complete(String id, String version) throws IOException {
        var dir = Files.createDirectories(tools.versionDirectory(id, version));
        tools.markComplete(dir);
        return dir;
    }

    @Test
    void layoutIsUnderDataDirTools() {
        assertThat(tools.root()).isEqualTo(dataDir.resolve("tools").toAbsolutePath().normalize());
        assertThat(tools.binDirectory()).isEqualTo(tools.root().resolve("bin"));
        assertThat(tools.stateDirectory("jdtls")).isEqualTo(tools.root().resolve("jdtls").resolve("state"));
        assertThat(tools.versionDirectory("jdtls", "1.2")).isEqualTo(tools.root().resolve("jdtls").resolve("1.2"));
    }

    @Test
    void invalidIdsAndVersionsAreRejected() {
        assertThatThrownBy(() -> tools.stateDirectory("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.installedVersion("Up")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.versionDirectory("jdtls", "..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialFolderIsNotAnInstalledVersion() throws IOException {
        Files.createDirectories(tools.versionDirectory("jdtls", "1.0.partial-ab"));
        assertThat(tools.installedVersion("jdtls")).isEmpty();
        complete("jdtls", "1.0");
        assertThat(tools.installedVersion("jdtls")).contains("1.0");
    }

    @Test
    void newestCompleteVersionWinsNumerically() throws IOException {
        complete("jdtls", "1.9.0-202601010000");
        complete("jdtls", "1.10.0-202501010000");
        Files.createDirectories(tools.versionDirectory("jdtls", "2.0.partial-x"));
        assertThat(tools.installedVersion("jdtls")).contains("1.10.0-202501010000");
    }

    @Test
    void deleteOtherVersionsKeepsOneAndState() throws IOException {
        complete("jdtls", "1.0");
        var kept = complete("jdtls", "2.0");
        Files.createDirectories(tools.versionDirectory("jdtls", "3.0.partial-x"));
        Files.createDirectories(tools.stateDirectory("jdtls"));

        tools.deleteOtherVersions("jdtls", "2.0");

        assertThat(kept).exists();
        assertThat(tools.versionDirectory("jdtls", "1.0")).doesNotExist();
        assertThat(tools.versionDirectory("jdtls", "3.0.partial-x")).doesNotExist();
        assertThat(tools.stateDirectory("jdtls")).exists();
    }

    @Test
    void deleteToolRemovesFolderAndLaunchers() throws IOException {
        complete("jdtls", "1.0");
        complete("kotlin-lsp", "1.0");
        Files.createDirectories(tools.binDirectory());
        Files.writeString(tools.binDirectory().resolve("jdtls.cmd"), "x");
        Files.writeString(tools.binDirectory().resolve("jdtls"), "x");
        Files.writeString(tools.binDirectory().resolve("kotlin-lsp.cmd"), "x");

        assertThat(tools.deleteTool("jdtls", List.of("jdtls"))).isEmpty();

        assertThat(tools.root().resolve("jdtls")).doesNotExist();
        assertThat(tools.binDirectory().resolve("jdtls.cmd")).doesNotExist();
        assertThat(tools.binDirectory().resolve("jdtls")).doesNotExist();
        assertThat(tools.binDirectory().resolve("kotlin-lsp.cmd")).exists();
        assertThat(tools.installedVersion("kotlin-lsp")).contains("1.0");
    }

    @Test
    void deleteToolOfUnknownIdSucceeds() {
        assertThat(tools.deleteTool("nothing", List.of())).isEmpty();
    }

    @Test
    void deleteToolReportsWhatStayedBehindAndStillRemovesTheFolder() throws IOException {
        complete("jdtls", "1.0");
        var blocked = Files.createDirectories(tools.binDirectory().resolve("jdtls").resolve("inner"));

        var leftovers = tools.deleteTool("jdtls", List.of("jdtls"));

        assertThat(leftovers).containsExactly(tools.binDirectory().resolve("jdtls"));
        assertThat(blocked).exists();
        assertThat(tools.root().resolve("jdtls")).doesNotExist();
    }

    @Test
    void prepareBinDirectoryCreatesIt() {
        tools.prepareBinDirectory();

        assertThat(tools.binDirectory()).isDirectory();
    }
}
