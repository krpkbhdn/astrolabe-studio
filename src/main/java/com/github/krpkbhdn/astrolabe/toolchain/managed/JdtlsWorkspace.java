package com.github.krpkbhdn.astrolabe.toolchain.managed;

import com.github.krpkbhdn.astrolabe.toolchain.managed.LauncherWriter.Platform;
import java.nio.file.Path;

/** The workspace folder the jdtls launcher derives from a project's working directory, so it can be found again. */
public final class JdtlsWorkspace {

    private JdtlsWorkspace() {
    }

    public static Path folderOf(Path stateDir, Path projectDirectory) {
        return folderOf(stateDir, projectDirectory, Platform.current());
    }

    public static Path folderOf(Path stateDir, Path projectDirectory, Platform platform) {
        return stateDir.resolve("workspaces").resolve(mangle(projectDirectory.toString(), platform));
    }

    /** The launcher's own replacement: cmd turns backslash, slash and colon into underscores, sh only the slash. */
    public static String mangle(String directory, Platform platform) {
        return platform == Platform.WINDOWS
                ? directory.replace('\\', '_').replace('/', '_').replace(':', '_')
                : directory.replace('/', '_');
    }

    public static Path markerFile(Path stateDir, Path projectDirectory) {
        return folderOf(stateDir, projectDirectory).resolve(".metadata").resolve(".plugins").resolve("dev.astrolabe.jdtls.output")
                .resolve("redirected");
    }
}
