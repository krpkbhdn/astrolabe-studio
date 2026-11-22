package com.github.krpkbhdn.astrolabe.claude.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import com.github.krpkbhdn.astrolabe.workspace.SystemHostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ClaudeInstallationServiceTest {

    private static final boolean WINDOWS = new SystemHostEnvironment().windows();

    @TempDir
    Path temp;

    @Test
    void parsesVersionFromCliOutput() {
        assertThat(ClaudeInstallationService.parseVersion("2.1.211 (Claude Code)\n")).contains("2.1.211");
        assertThat(ClaudeInstallationService.parseVersion("garbage")).isEmpty();
        assertThat(ClaudeInstallationService.parseVersion(null)).isEmpty();
    }

    @Test
    void notFoundProblemNamesInstallCommandPerOs() {
        assertThat(ClaudeInstallationService.notFoundProblem(true)).contains("install.ps1");
        assertThat(ClaudeInstallationService.notFoundProblem(false)).contains("install.sh");
    }

    @Test
    void reportsMissingBinaryWithInstallHint() {
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, temp);

        var result = new ClaudeInstallationService(host, new ClaudeBinaryLocator()).recheck();

        assertThat(result.installed()).isFalse();
        assertThat(result.problem()).contains("curl -fsSL");
    }

    @Test
    void detectsVersionAndLoggedInStateFromFakeExecutable() throws IOException {
        var result = detectWithFakeClaude(0);

        assertThat(result.installed()).isTrue();
        assertThat(result.version()).isEqualTo("9.8.7");
        assertThat(result.login()).isEqualTo(LoginState.LOGGED_IN);
    }

    @Test
    void mapsAuthExitCodeOneToLoggedOutAndOthersToUnknown() throws IOException {
        assertThat(detectWithFakeClaude(1).login()).isEqualTo(LoginState.LOGGED_OUT);
        assertThat(detectWithFakeClaude(7).login()).isEqualTo(LoginState.UNKNOWN);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void versionCommandSeesToolsBesideClaudeEvenWithEmptyPath() throws IOException {
        var dir = Files.createDirectories(temp.resolve(".nvm/versions/node/v1.0.0/bin"));
        var node = dir.resolve("fakenode");
        Files.writeString(node, "#!/bin/sh\n");
        assertThat(node.toFile().setExecutable(true)).isTrue();
        var script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\ncommand -v fakenode >/dev/null || exit 3\necho 9.8.7\nexit 0\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        var host = new SystemHostEnvironment() {
            @Override
            public Path home() {
                return temp;
            }

            @Override
            public List<Path> pathDirectories() {
                return List.of();
            }

            @Override
            public String env(String name) {
                return name.equals("PATH") ? "/usr/bin:/bin" : null;
            }
        };

        assertThat(new ClaudeInstallationService(host, new ClaudeBinaryLocator()).recheck().version()).isEqualTo("9.8.7");
    }

    private ClaudeInstallation detectWithFakeClaude(int authExit) throws IOException {
        var dir = Files.createTempDirectory(temp, "bin");
        var script = dir.resolve(WINDOWS ? "claude.cmd" : "claude");
        Files.writeString(script, WINDOWS ? windowsScript(authExit) : unixScript(authExit));
        assertThat(script.toFile().setExecutable(true)).isTrue();
        var host = new FakeHostEnvironmentWithRealFiles(dir);
        return new ClaudeInstallationService(host, new ClaudeBinaryLocator()).recheck();
    }

    private static String windowsScript(int authExit) {
        return "@echo off\r\nif \"%1\"==\"--version\" (echo 9.8.7 (Claude Code) & exit /b 0)\r\nexit /b " + authExit + "\r\n";
    }

    private static String unixScript(int authExit) {
        return "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo '9.8.7 (Claude Code)'; exit 0; fi\nexit " + authExit + "\n";
    }

    /** Real file checks over a single PATH directory, so the fake script is found like a real install. */
    private static final class FakeHostEnvironmentWithRealFiles extends SystemHostEnvironment {
        private final Path directory;

        FakeHostEnvironmentWithRealFiles(Path directory) {
            this.directory = directory;
        }

        @Override
        public List<Path> pathDirectories() {
            return List.of(directory);
        }

        @Override
        public Path home() {
            return directory;
        }
    }
}
