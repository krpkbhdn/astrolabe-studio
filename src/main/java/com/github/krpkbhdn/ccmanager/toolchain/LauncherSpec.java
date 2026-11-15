package com.github.krpkbhdn.ccmanager.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * How the app starts an unpacked tool: BINARY wraps the file at path with fixed args, JDTLS builds its own Equinox command (no path).
 * In an arg, {@link #STATE_PLACEHOLDER} stands for the tool's state directory and {@link #INSTALL_PLACEHOLDER} for the unpacked version folder.
 * A routed launcher is a workspace router: it rejects the caller's own arguments (they are not passed on) and an initialize without a workspace folder.
 * The fallback launcher is used when the unpacked archive has no file at path (an older version).
 */
public record LauncherSpec(Kind kind, String path, List<String> args, Boolean routed, LauncherSpec fallback) {

    public static final String STATE_PLACEHOLDER = "{state}";
    public static final String INSTALL_PLACEHOLDER = "{install}";

    public enum Kind { BINARY, JDTLS }

    public LauncherSpec {
        Objects.requireNonNull(kind);
        path = path == null ? "" : path;
        routed = routed != null && routed;
        args = args == null ? List.of() : List.copyOf(args);
    }

    public LauncherSpec(Kind kind, String path, List<String> args) {
        this(kind, path, args, false, null);
    }

    public LauncherSpec(Kind kind, String path) {
        this(kind, path, List.of());
    }

    public List<String> argumentsFor(Path stateDirectory, Path installDirectory) {
        return args.stream().map(arg -> argumentFor(arg, stateDirectory, installDirectory)).toList();
    }

    private static String argumentFor(String arg, Path stateDirectory, Path installDirectory) {
        if (arg.equals(STATE_PLACEHOLDER)) {
            return stateDirectory.toString();
        }
        return arg.startsWith(INSTALL_PLACEHOLDER + "/") ? installDirectory.resolve(arg.substring(INSTALL_PLACEHOLDER.length() + 1)).toString() : arg;
    }
}
