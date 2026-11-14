package com.github.krpkbhdn.ccmanager.extension;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Where Claude Code stores an extension: for the user, shared in the project, or private to this project; PLUGIN marks MCP servers a plugin provides (read-only, Code Mode only). */
public enum ExtensionScope {
    USER, PROJECT, LOCAL, PLUGIN;

    /** The value of the CLI's {@code --scope} option; PLUGIN has none, the CLI knows only user, project and local. */
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
            if (scope != PLUGIN && scope.cliValue().equals(value)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
