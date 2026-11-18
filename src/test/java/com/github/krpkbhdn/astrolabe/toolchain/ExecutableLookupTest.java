package com.github.krpkbhdn.astrolabe.toolchain;

import static com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem.LINUX;
import static com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem.WINDOWS;
import static com.github.krpkbhdn.astrolabe.toolchain.ExecutableLookups.DATA_DIR;
import static com.github.krpkbhdn.astrolabe.toolchain.ExecutableLookups.lookup;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExecutableLookupTest {

    private static final Path HOME = Path.of("home", "me");
    private static final Path BIN = Path.of("usr", "bin");
    private static final Path MANAGED_BIN = DATA_DIR.resolve("tools").resolve("bin");

    @Test
    void findsOnPath() {
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withFile(BIN.resolve("gopls"));

        assertThat(lookup(host).find("gopls")).contains(BIN.resolve("gopls"));
    }

    @Test
    void windowsTriesExtensionsAndPrefersExe() {
        var host = new FakeHostEnvironment(WINDOWS, HOME).withPath(BIN)
                .withFile(BIN.resolve("tool.cmd")).withFile(BIN.resolve("tool.exe"));

        assertThat(lookup(host).find("tool")).contains(BIN.resolve("tool.exe"));
        assertThat(lookup(new FakeHostEnvironment(WINDOWS, HOME).withPath(BIN).withFile(BIN.resolve("tool.cmd")))
                .find("tool")).contains(BIN.resolve("tool.cmd"));
    }

    @Test
    void fallsBackToUserBinDirectories() {
        var goBin = HOME.resolve("go").resolve("bin").resolve("gopls");
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withFile(goBin);

        assertThat(lookup(host).find("gopls")).contains(goBin);
    }

    @Test
    void windowsChecksAppDataNpm() {
        var appData = Path.of("appdata");
        var shim = appData.resolve("npm").resolve("typescript-language-server.cmd");
        var host = new FakeHostEnvironment(WINDOWS, HOME).withEnv("APPDATA", appData.toString()).withFile(shim);

        assertThat(lookup(host).find("typescript-language-server")).contains(shim);
    }

    @Test
    void windowsChecksWellKnownInstallDirectories() {
        var node = Path.of("pf").resolve("nodejs").resolve("npm.cmd");
        var python = Path.of("local").resolve("Programs/Python").resolve("Python313").resolve("Scripts").resolve("pip.exe");
        var host = new FakeHostEnvironment(WINDOWS, HOME).withEnv("ProgramFiles", "pf").withEnv("LOCALAPPDATA", "local")
                .withFile(node).withFile(python)
                .withDirectory(Path.of("local").resolve("Programs/Python").resolve("Python39"))
                .withDirectory(Path.of("local").resolve("Programs/Python").resolve("Python313"));

        assertThat(lookup(host).find("npm")).contains(node);
        assertThat(lookup(host).find("pip")).contains(python);
    }

    @Test
    void unixChecksHomebrewAndUsrLocal() {
        var go = Path.of("/usr/local/go/bin").resolve("go");

        assertThat(lookup(new FakeHostEnvironment(LINUX, HOME).withFile(go)).find("go")).contains(go);
    }

    @Test
    void pathDirectoryWinsOverExtrasEvenWithAnotherExtension() {
        var appData = Path.of("appdata");
        var shim = BIN.resolve("tool.cmd");
        var host = new FakeHostEnvironment(WINDOWS, HOME).withPath(BIN).withEnv("APPDATA", appData.toString())
                .withFile(shim).withFile(appData.resolve("npm").resolve("tool.exe"));

        assertThat(lookup(host).find("tool")).contains(shim);
    }

    @Test
    void searchPathListsProcessPathBeforeExistingLookupFoldersWithoutDuplicates() {
        var userBin = HOME.resolve(".local").resolve("bin");
        var goBin = HOME.resolve("go").resolve("bin");
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN, userBin).withFile(userBin.resolve("tool"))
                .withFile(goBin.resolve("go"));

        assertThat(lookup(host).searchPath()).isEqualTo(BIN + ":" + userBin + ":" + MANAGED_BIN + ":" + goBin);
    }

    @Test
    void sameNamedToolOnPathShadowsExtrasInFindAndSearchPath() {
        var goBin = HOME.resolve("go").resolve("bin");
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withFile(BIN.resolve("go")).withFile(goBin.resolve("go"));
        var lookup = lookup(host);

        assertThat(lookup.find("go")).contains(BIN.resolve("go"));
        assertThat(lookup.searchPath()).startsWith(BIN.toString());
    }

    @Test
    void windowsEnvironmentUsesSemicolonsAndBlocksCurrentDirectoryLookup() {
        var node = Path.of("pf").resolve("nodejs").resolve("npm.cmd");
        var host = new FakeHostEnvironment(WINDOWS, HOME).withPath(BIN).withEnv("ProgramFiles", "pf").withFile(node);

        var environment = lookup(host).environment();

        assertThat(environment.get("PATH")).isEqualTo(BIN + ";" + MANAGED_BIN + ";" + node.getParent());
        assertThat(environment).containsEntry("NoDefaultCurrentDirectoryInExePath", "1");
    }

    @Test
    void managedBinIsTheFirstExtraDirectoryButStaysAfterThePath() {
        var managedBin = DATA_DIR.resolve("tools").resolve("bin");
        var goBin = HOME.resolve("go").resolve("bin");
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withDirectory(managedBin).withDirectory(goBin)
                .withFile(managedBin.resolve("jdtls")).withFile(goBin.resolve("jdtls"));

        assertThat(lookup(host).find("jdtls")).contains(managedBin.resolve("jdtls"));
        assertThat(lookup(host).searchPath()).isEqualTo(BIN + ":" + MANAGED_BIN + ":" + goBin);
        assertThat(lookup(host.withFile(BIN.resolve("jdtls"))).find("jdtls")).contains(BIN.resolve("jdtls"));
    }

    @Test
    void reportsMissing() {
        assertThat(lookup(new FakeHostEnvironment(LINUX, HOME).withPath(BIN)).find("nothing")).isEmpty();
    }

    @Test
    void searchPathKeepsRawQuotedWindowsEntryVerbatim() {
        var raw = "\"C:\\Program Files\\Foo\";C:\\bin";
        var host = new FakeHostEnvironment(WINDOWS, HOME).withEnv("PATH", raw);

        assertThat(lookup(host).searchPath()).isEqualTo(raw + ";" + MANAGED_BIN);
    }

    @Test
    void searchPathAppendsAnExtraOnlyWhenMissing() {
        var goBin = HOME.resolve("go").resolve("bin");
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withDirectory(goBin);

        assertThat(lookup(host).searchPath()).isEqualTo(BIN + ":" + MANAGED_BIN + ":" + goBin);
    }

    @Test
    void searchPathDoesNotRepeatAnExtraWrittenWithOtherCaseOrTrailingSeparatorOnWindows() {
        var goBin = HOME.resolve("go").resolve("bin");
        var raw = "C:\\bin;" + goBin.toString().toUpperCase() + "\\";
        var host = new FakeHostEnvironment(WINDOWS, HOME).withEnv("PATH", raw).withDirectory(goBin);

        assertThat(lookup(host).searchPath()).isEqualTo(raw + ";" + MANAGED_BIN);
    }
}
