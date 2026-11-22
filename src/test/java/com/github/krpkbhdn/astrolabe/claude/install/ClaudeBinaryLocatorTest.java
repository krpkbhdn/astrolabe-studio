package com.github.krpkbhdn.astrolabe.claude.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.LoginShellPath;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ClaudeBinaryLocatorTest {

    private static final Path WINDOWS_HOME = Path.of("C:\\Users\\u");
    private static final String APPDATA = "C:\\Users\\u\\AppData\\Roaming";
    private static final Path NPM_DIRECTORY = Path.of(APPDATA, "npm");

    private final ClaudeBinaryLocator locator = new ClaudeBinaryLocator();

    @Test
    void unixCandidatesPutPathBeforeNativeInstallerAndFallbacks() {
        var home = Path.of("/home/u");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, home).withPath(Path.of("/a"), Path.of("/b"));

        assertThat(locator.candidates(host)).startsWith(
                Path.of("/a/claude"), Path.of("/b/claude"), home.resolve(".local/bin/claude"),
                Path.of("/usr/local/bin/claude"), Path.of("/opt/homebrew/bin/claude"),
                home.resolve(".npm-global/bin/claude"));
    }

    @Test
    void unixFindsNpmGlobalBinWithEmptyPath() {
        var home = Path.of("/home/u");
        var npm = home.resolve(".npm-global/bin/claude");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, home).withFile(npm);

        assertThat(locator.find(host)).contains(npm);
    }

    @Test
    void unixFindsClaudeInNvmNodeVersionWithEmptyPath() {
        var home = Path.of("/home/u");
        var claude = home.resolve(".nvm/versions/node/v22.1.0/bin/claude");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, home).withFile(claude);

        assertThat(locator.find(host)).contains(claude);
    }

    @Test
    void configuredPathIsCheckedBeforePath() {
        var home = Path.of("/home/u").toAbsolutePath();
        var custom = Path.of("/custom/claude").toAbsolutePath();
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, home).withPath(Path.of("/a")).withFile(Path.of("/a/claude")).withFile(custom);
        var configured = new ClaudeBinaryLocator(new ClaudeProperties(custom.toString()), LoginShellPath.disabled());
        var relative = new ClaudeBinaryLocator(new ClaudeProperties("relative/claude"), LoginShellPath.disabled());

        assertThat(configured.find(host)).contains(custom);
        assertThat(relative.candidates(host)).first().isEqualTo(Path.of("/a/claude"));
    }

    @Test
    void unixEnvironmentPutsTheClaudeFolderFirstAndWindowsHasNone() {
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, Path.of("/home/u")).withPath(Path.of("/a"));
        var windows = new FakeHostEnvironment(OperatingSystem.WINDOWS, WINDOWS_HOME);

        assertThat(locator.environmentFor(host, Path.of("/n/bin/claude"))).containsEntry("PATH", Path.of("/n/bin") + ":" + Path.of("/a"));
        assertThat(locator.environmentFor(windows, Path.of("C:\\x\\claude.exe"))).isEmpty();
    }

    @Test
    void unixPnpmHomeIsUsedOnlyWhenSet() {
        var pnpm = Path.of("/pnpm").toAbsolutePath();
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, Path.of("/home/u"));
        assertThat(locator.candidates(host)).doesNotContain(pnpm.resolve("claude"));

        host.withEnv("PNPM_HOME", pnpm.toString());
        assertThat(locator.candidates(host)).contains(pnpm.resolve("claude"));
    }

    @Test
    void windowsCandidatesTryEveryExeBeforeAnyCmdShim() {
        var host = new FakeHostEnvironment(OperatingSystem.WINDOWS, WINDOWS_HOME).withPath(Path.of("C:\\tools"));
        var candidates = locator.candidates(host);

        assertThat(candidates).startsWith(
                Path.of("C:\\tools\\claude.exe"), WINDOWS_HOME.resolve(".local").resolve("bin").resolve("claude.exe"));
        assertThat(candidates.indexOf(Path.of("C:\\tools\\claude.cmd"))).isGreaterThan(
                candidates.indexOf(WINDOWS_HOME.resolve("scoop").resolve("shims").resolve("claude.exe")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsFindsNpmShimWithEmptyPath() {
        var shim = NPM_DIRECTORY.resolve("claude.cmd");
        var host = new FakeHostEnvironment(OperatingSystem.WINDOWS, WINDOWS_HOME).withEnv("APPDATA", APPDATA).withFile(shim);

        assertThat(locator.find(host)).contains(shim);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsPrefersRealExeBesideNpmShim() {
        var exe = NPM_DIRECTORY.resolve("node_modules").resolve("@anthropic-ai").resolve("claude-code")
                .resolve("bin").resolve("claude.exe");
        var host = new FakeHostEnvironment(OperatingSystem.WINDOWS, WINDOWS_HOME).withEnv("APPDATA", APPDATA)
                .withFile(NPM_DIRECTORY.resolve("claude.cmd")).withFile(exe);

        assertThat(locator.find(host)).contains(exe);
    }

    @Test
    void relativeAndBlankEnvValuesAreIgnored() {
        var host = new FakeHostEnvironment(OperatingSystem.WINDOWS, WINDOWS_HOME)
                .withEnv("APPDATA", "foo").withEnv("LOCALAPPDATA", " ");

        assertThat(locator.candidates(host)).doesNotContain(Path.of("foo", "npm", "claude.exe"));
        assertThat(locator.candidates(host)).noneMatch(path -> path.endsWith(Path.of("pnpm", "claude.exe")));
    }
}
