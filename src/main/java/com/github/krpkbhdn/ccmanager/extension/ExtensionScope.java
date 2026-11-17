package com.github.krpkbhdn.ccmanager.extension;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Where an extension is stored: for the user, shared in the project's folder, private to this project (the last two are Claude Code's), kept by the app outside the project folder (APP, the project-level scope for registered projects), or provided by a plugin (PLUGIN, read-only, Code Mode only). */
public enum ExtensionScope {
    USER, PROJECT, LOCAL, PLUGIN, APP;

    /** The value of the CLI's {@code --scope} option; PLUGIN and APP have none, the CLI knows only user, project and local. */
    public String cliValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the directory a scoped CLI command must run in: the project, or null for user scope */
    public Path workingDirectory(Path projectDirectory) {
        if (this == USER) {
            return null;
        }
        if (projectDirectory == null) {
            throw new ExtensionException(cliValue() + " scope needs a project");
        }
        return projectDirectory;
    }

    public static Optional<ExtensionScope> fromCli(String value) {
        for (var scope : values()) {
            if (scope != PLUGIN && scope != APP && scope.cliValue().equals(value)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
