package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnixToolDirectoriesTest {

    private static final Path HOME = Path.of("/home/u").toAbsolutePath();

    @Test
    void nvmVersionsAreListedNewestFirstAndOnlyWhenTheyExist() {
        var nvm = HOME.resolve(".nvm/versions/node");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, HOME)
                .withFile(nvm.resolve("v9.11.2/bin/claude")).withFile(nvm.resolve("v22.3.0/bin/node")).withFile(nvm.resolve("v22.12.0/bin/node"));

        assertThat(UnixToolDirectories.of(host)).containsExactly(
                nvm.resolve("v22.12.0/bin"), nvm.resolve("v22.3.0/bin"), nvm.resolve("v9.11.2/bin"));
    }

    @Test
    void nvmDirVariableMovesTheSearch() {
        var custom = Path.of("/opt/nvm").toAbsolutePath();
        var bin = custom.resolve("versions/node/v20.0.0/bin");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, HOME).withEnv("NVM_DIR", custom.toString()).withFile(bin.resolve("node"));

        assertThat(UnixToolDirectories.of(host)).containsExactly(bin);
    }

    @Test
    void fnmAsdfAndMiseShimsAreIncluded() {
        var fnm = HOME.resolve(".local/share/fnm/node-versions/v20.1.0/installation/bin");
        var asdf = HOME.resolve(".asdf/shims");
        var mise = HOME.resolve(".local/share/mise/shims");
        var host = new FakeHostEnvironment(OperatingSystem.LINUX, HOME).withFile(fnm.resolve("node")).withDirectory(asdf).withDirectory(mise);

        assertThat(UnixToolDirectories.of(host)).containsExactly(fnm, asdf, mise);
    }
}
