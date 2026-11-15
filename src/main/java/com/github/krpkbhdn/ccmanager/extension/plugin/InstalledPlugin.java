package com.github.krpkbhdn.ccmanager.extension.plugin;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import java.nio.file.Path;
import java.util.Objects;

/** One installation of a plugin; projectPath is null for user scope. */
public record InstalledPlugin(
        String id, String version, ExtensionScope scope, boolean enabled, Path installPath, Path projectPath) {

    /** True when this is the installation of plugin {@code wantedId} in the scope; project scope also needs the same project folder. */
    public boolean isInstallationOf(String wantedId, ExtensionScope wantedScope, Path directory) {
        return id.equals(wantedId) && scope == wantedScope
                && (wantedScope == ExtensionScope.USER || Objects.equals(projectPath, directory));
    }
}
