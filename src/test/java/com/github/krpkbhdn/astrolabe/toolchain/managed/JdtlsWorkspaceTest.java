package com.github.krpkbhdn.astrolabe.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.toolchain.managed.LauncherWriter.Platform;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdtlsWorkspaceTest {

    @Test
    void windowsManglingReplacesBackslashSlashAndColon() {
        assertThat(JdtlsWorkspace.mangle("C:\\Work\\my/app", Platform.WINDOWS)).isEqualTo("C__Work_my_app");
    }

    @Test
    void posixManglingReplacesOnlySlashes() {
        assertThat(JdtlsWorkspace.mangle("/home/u/my:app", Platform.LINUX)).isEqualTo("_home_u_my:app");
        assertThat(JdtlsWorkspace.mangle("/Users/u/app", Platform.MAC)).isEqualTo("_Users_u_app");
    }

    @Test
    void folderAndMarkerLiveUnderTheStateWorkspaces() {
        var state = Path.of("state");
        var project = Path.of("proj", "app");
        var folder = JdtlsWorkspace.folderOf(state, project, Platform.current());

        assertThat(folder.getParent()).isEqualTo(state.resolve("workspaces"));
        assertThat(JdtlsWorkspace.markerFile(state, project))
                .isEqualTo(folder.resolve(".metadata").resolve(".plugins").resolve("dev.astrolabe.jdtls.output").resolve("redirected"));
    }
}
