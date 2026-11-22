package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class LoginShellPathTest {

    @TempDir
    Path temp;

    @Test
    void readsTheLastLineAndKeepsOnlyExistingAbsoluteDirectories() throws IOException {
        var bin = Files.createDirectory(temp.resolve("nvm-bin"));
        var shell = shell("echo banner; echo '" + bin + ":/does/not/exist:relative/dir:" + bin + "'");

        assertThat(probe(5).directories(host(shell.toString()))).containsExactly(bin);
    }

    @Test
    void isCachedUntilInvalidated() throws IOException {
        var bin = Files.createDirectory(temp.resolve("bin"));
        var shell = shell("echo '" + bin + "'");
        var probe = probe(5);
        assertThat(probe.directories(host(shell.toString()))).containsExactly(bin);

        Files.delete(shell);
        assertThat(probe.directories(host(shell.toString()))).containsExactly(bin);
        probe.invalidate();
        assertThat(probe.directories(host(shell.toString()))).isEmpty();
    }

    @Test
    void timedOutShellYieldsNothing() throws IOException {
        var shell = shell("sleep 30");
        var started = System.nanoTime();

        // /bin/sh is the fallback when the user shell fails, hide it so the timeout alone decides
        var withoutFallback = new SystemHostEnvironment() {
            @Override
            public String env(String name) {
                return name.equals("SHELL") ? shell.toString() : super.env(name);
            }

            @Override
            public boolean isFile(Path path) {
                return !path.equals(Path.of("/bin/sh")) && super.isFile(path);
            }
        };

        assertThat(probe(1).directories(withoutFallback)).isEmpty();
        assertThat(System.nanoTime() - started).isLessThan(20_000_000_000L);
    }

    @Test
    void unsetOrRelativeShellIsSkipped() {
        assertThat(probe(1).directories(host("sh"))).isEmpty();
        assertThat(probe(1).directories(host(null))).isEmpty();
    }

    private static LoginShellPath probe(int timeoutSeconds) {
        return new LoginShellPath(new LoginShellProperties(true, timeoutSeconds));
    }

    private Path shell(String body) throws IOException {
        var script = Files.createTempFile(temp, "shell", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private static HostEnvironment host(String shell) {
        return new SystemHostEnvironment() {
            @Override
            public String env(String name) {
                return name.equals("SHELL") ? shell : super.env(name);
            }
        };
    }
}
