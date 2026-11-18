package com.github.krpkbhdn.astrolabe.extension.plugin;

import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import java.nio.file.Path;

/** One installation of a plugin; projectPath is null for user scope. */
public record InstalledPlugin(
        String id, String version, ExtensionScope scope, boolean enabled, Path installPath, Path projectPath) {

    /** True when this is the installation of plugin {@code wantedId} in the scope; project scope also needs the same project folder. */
    public boolean isInstallationOf(String wantedId, ExtensionScope wantedScope, Path directory) {
        return id.equals(wantedId) && scope == wantedScope
                && (wantedScope == ExtensionScope.USER || sameDirectory(projectPath, directory));
    }

    /** Same folder regardless of relative form or dot segments; two nulls are equal. */
    public static boolean sameDirectory(Path first, Path second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }
}
