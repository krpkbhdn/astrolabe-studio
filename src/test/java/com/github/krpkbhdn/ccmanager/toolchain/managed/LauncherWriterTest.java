package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.toolchain.managed.LauncherWriter.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherWriterTest {

    @TempDir
    Path dataDir;

    ManagedToolsDirectory tools;
    LauncherWriter writer;

    @BeforeEach
    void setUp() {
        tools = new ManagedToolsDirectory(new AppProperties(dataDir));
        writer = new LauncherWriter(tools);
    }

    private Path jdtlsInstall() throws IOException {
        var install = tools.versionDirectory("jdtls", "1.0");
        Files.createDirectories(install.resolve("plugins"));
        Files.writeString(install.resolve("plugins").resolve("org.eclipse.equinox.launcher_1.7.0.jar"), "");
        Files.writeString(install.resolve("plugins").resolve("org.eclipse.equinox.launcher_1.6.0.jar"), "");
        return install;
    }

    @Test
    void windowsBinaryLauncherIsGolden() throws IOException {
        var binary = tools.versionDirectory("kotlin-lsp", "1.0").resolve("bin").resolve("intellij-server.exe");

        var launcher = writer.writeBinary("kotlin-lsp", binary, Platform.WINDOWS);

        assertThat(launcher).isEqualTo(tools.binDirectory().resolve("kotlin-lsp.cmd"));
        assertThat(Files.readString(launcher)).isEqualTo("@echo off\r\n\"" + binary + "\" %*\r\n");
    }

    @Test
    void posixBinaryLauncherIsGolden() throws IOException {
        var binary = tools.versionDirectory("kotlin-lsp", "1.0").resolve("bin").resolve("intellij-server");

        var launcher = writer.writeBinary("kotlin-lsp", binary, Platform.LINUX);

        assertThat(launcher).isEqualTo(tools.binDirectory().resolve("kotlin-lsp"));
        assertThat(Files.readString(launcher)).isEqualTo("#!/bin/sh\nexec '" + binary + "' \"$@\"\n");
        if (launcher.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.isExecutable(launcher)).isTrue();
        }
    }

    @Test
    void binaryLauncherPassesFixedArgumentsBeforeTheCallersOwn() throws IOException {
        var binary = Path.of("bin", "intellij-server");
        var state = tools.stateDirectory("kotlin-lsp");
        var args = java.util.List.of("--system-path", state.toString());

        var windows = Files.readString(writer.writeBinary("kotlin-lsp", binary, args, Platform.WINDOWS));
        var posix = Files.readString(writer.writeBinary("kotlin-lsp", binary, args, Platform.LINUX));

        assertThat(windows).isEqualTo("@echo off\r\n\"" + binary + "\" \"--system-path\" \"" + state + "\" %*\r\n");
        assertThat(posix).isEqualTo("#!/bin/sh\nexec '" + binary + "' '--system-path' '" + state + "' \"$@\"\n");
    }

    @Test
    void windowsArgumentWithCmdMetacharacterIsRefused() {
        assertThatThrownBy(() -> writer.writeBinary("kotlin-lsp", Path.of("x"), java.util.List.of("a&b"), Platform.WINDOWS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relaunchReplacesTheLauncher()throws IOException {
        writer.writeBinary("kotlin-lsp", Path.of("a"), Platform.LINUX);
        var launcher = writer.writeBinary("kotlin-lsp", Path.of("b"), Platform.LINUX);

        assertThat(Files.readString(launcher)).contains("'b'");
        assertThat(tools.binDirectory()).isDirectoryContaining(path -> path.getFileName().toString().equals("kotlin-lsp"));
        try (var files = Files.list(tools.binDirectory())) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void windowsJdtlsLauncherIsGolden() throws IOException {
        var install = jdtlsInstall();
        var state = tools.stateDirectory("jdtls");

        var text = Files.readString(writer.writeJdtls("jdtls", install, state, Platform.WINDOWS));

        assertThat(text).isEqualTo("@echo off\r\nsetlocal\r\nset \"WS=%CD%\"\r\nset \"WS=%WS:\\=_%\"\r\nset \"WS=%WS:/=_%\"\r\nset \"WS=%WS::=_%\"\r\n"
                + "java -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4"
                + " -Declipse.product=org.eclipse.jdt.ls.core.product -Dosgi.checkConfiguration=true"
                + " \"-Dosgi.sharedConfiguration.area=" + install.resolve("config_win") + "\""
                + " -Dosgi.sharedConfiguration.area.readOnly=true -Dosgi.configuration.cascaded=false -Xms1G --add-modules=ALL-SYSTEM"
                + " --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"
                + " -jar \"" + install.resolve("plugins").resolve("org.eclipse.equinox.launcher_1.7.0.jar") + "\""
                + " -configuration \"" + state.resolve("config") + "\""
                + " -data \"" + state.resolve("workspaces") + "\\%WS%\" %*\r\n");
    }

    @Test
    void posixJdtlsLauncherIsGoldenPerOs() throws IOException {
        var install = jdtlsInstall();
        var state = tools.stateDirectory("jdtls");

        var linux = Files.readString(writer.writeJdtls("jdtls", install, state, Platform.LINUX));
        var mac = Files.readString(writer.writeJdtls("jdtls", install, state, Platform.MAC));

        assertThat(linux).startsWith("#!/bin/sh\nWS=$(pwd | tr '/' '_')\nexec java -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Declipse.application=org.eclipse.jdt.ls.core.id1 ")
                .contains("'-Dosgi.sharedConfiguration.area=" + install.resolve("config_linux") + "'")
                .contains(" -jar '" + install.resolve("plugins").resolve("org.eclipse.equinox.launcher_1.7.0.jar") + "'")
                .endsWith(" -data '" + state.resolve("workspaces") + "/'\"$WS\" \"$@\"\n");
        assertThat(mac).contains("config_mac").doesNotContain("config_linux");
    }

    @Test
    void posixJdtlsDataPrefixWithShellMetacharactersIsSingleQuoted() throws IOException {
        var install = jdtlsInstall();
        var windowsHost = java.io.File.separatorChar == '\\';
        var state = Path.of(windowsHost ? "/st$ate `x` y's" : "/st$ate \"q\" `x` \\y's");

        var text = Files.readString(writer.writeJdtls("jdtls", install, state, Platform.LINUX));

        assertThat(text).endsWith(" -data '" + (state.resolve("workspaces") + "/").replace("'", "'\\''") + "'\"$WS\" \"$@\"\n");
        assertThat(text).contains("st$ate");
    }

    @Test
    void jdtlsPicksTheNewestLauncherJarByNumericVersion() throws IOException {
        var install = jdtlsInstall();
        Files.writeString(install.resolve("plugins").resolve("org.eclipse.equinox.launcher_1.10.0.jar"), "");

        var text = Files.readString(writer.writeJdtls("jdtls", install, tools.stateDirectory("jdtls"), Platform.LINUX));

        assertThat(text).contains("org.eclipse.equinox.launcher_1.10.0.jar");
    }

    @Test
    void windowsLauncherWithNonAsciiPathIsRefused() {
        assertThatThrownBy(() -> writer.writeBinary("kotlin-lsp", Path.of("C:\\Users\\Бодя\\server.exe"), Platform.WINDOWS))
                .isInstanceOf(IOException.class).hasMessageContaining("non-ASCII");
        assertThat(tools.binDirectory().resolve("kotlin-lsp.cmd")).doesNotExist();
    }

    @Test
    void missingLauncherJarFails() throws IOException {
        var install = Files.createDirectories(tools.versionDirectory("jdtls", "1.0").resolve("plugins")).getParent();

        assertThatThrownBy(() -> writer.writeJdtls("jdtls", install, tools.stateDirectory("jdtls"), Platform.LINUX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void windowsPathWithCmdMetacharacterIsRefused() {
        assertThatThrownBy(() -> writer.writeBinary("kotlin-lsp", Path.of("C:\\a&b\\server.exe"), Platform.WINDOWS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tools.binDirectory().resolve("kotlin-lsp.cmd")).doesNotExist();
    }

    @Test
    void launcherNameCannotEscapeBinDirectory() {
        assertThatThrownBy(() -> writer.writeBinary("..\\evil", Path.of("x"), Platform.WINDOWS)).isInstanceOf(IllegalArgumentException.class);
    }
}
