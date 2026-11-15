package com.github.krpkbhdn.ccmanager.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * How the app starts an unpacked tool: BINARY wraps the file at path with fixed args, JDTLS builds its own Equinox command (no path).
 * An arg equal to {@link #STATE_PLACEHOLDER} stands for the tool's state directory.
 */
public record LauncherSpec(Kind kind, String path, List<String> args) {

    public static final String STATE_PLACEHOLDER = "{state}";

    public enum Kind { BINARY, JDTLS }

    public LauncherSpec {
        Objects.requireNonNull(kind);
        path = path == null ? "" : path;
        args = args == null ? List.of() : List.copyOf(args);
    }

    public LauncherSpec(Kind kind, String path) {
        this(kind, path, List.of());
    }

    public List<String> argumentsFor(Path stateDirectory) {
        return args.stream().map(arg -> arg.equals(STATE_PLACEHOLDER) ? stateDirectory.toString() : arg).toList();
    }
}
