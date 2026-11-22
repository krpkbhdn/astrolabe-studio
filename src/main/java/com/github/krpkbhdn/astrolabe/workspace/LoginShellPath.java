package com.github.krpkbhdn.astrolabe.workspace;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The PATH a login shell builds, read once by asking the user's own shell ($SHELL -ilc), so nvm, fnm and the like are found
 * whatever the shell (bash, zsh, fish, sh) and without editing any rc file. Unix only; a failure yields no directories.
 */
@Component
@EnableConfigurationProperties(LoginShellProperties.class)
public class LoginShellPath {

    private static final Logger log = LoggerFactory.getLogger(LoginShellPath.class);
    private static final Path POSIX_SHELL = Path.of("/bin/sh");
    private static final String PRINT_PATH = "printenv PATH";
    private static final String PATH_SEPARATOR = ":";

    private final LoginShellProperties properties;
    private volatile List<Path> cached;

    public LoginShellPath(LoginShellProperties properties) {
        this.properties = properties;
    }

    public static LoginShellPath disabled() {
        return new LoginShellPath(new LoginShellProperties(false, 1));
    }

    /** Existing absolute directories of the login PATH; probed on first use and kept until {@link #invalidate}. */
    public List<Path> directories(HostEnvironment host) {
        if (!properties.enabled() || host.windows()) {
            return List.of();
        }
        var result = cached;
        if (result == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = probe(host);
                }
                result = cached;
            }
        }
        return result;
    }

    public void invalidate() {
        cached = null;
    }

    private List<Path> probe(HostEnvironment host) {
        var shell = host.env("SHELL");
        if (shell == null || shell.isBlank() || !isUsable(host, shell)) {
            return List.of();
        }
        var directories = run(host, List.of(shell, "-ilc", PRINT_PATH));
        if (directories.isEmpty() && host.isFile(POSIX_SHELL)) {
            directories = run(host, List.of(POSIX_SHELL.toString(), "-lc", PRINT_PATH));
        }
        return directories;
    }

    private static boolean isUsable(HostEnvironment host, String shell) {
        try {
            var path = Path.of(shell);
            return path.isAbsolute() && host.isFile(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private List<Path> run(HostEnvironment host, List<String> command) {
        try {
            var result = CommandRunner.run(command, null, properties.timeout());
            if (!result.succeeded()) {
                return List.of();
            }
            // rc files may print banners first; the PATH is the last line
            var line = result.stdout().lines().map(String::strip).filter(text -> !text.isEmpty()).reduce((first, last) -> last);
            return line.map(text -> validated(host, text)).orElse(List.of());
        } catch (IOException e) {
            log.debug("Login shell probe could not run {}: {}", command.getFirst(), e.getMessage());
            return List.of();
        }
    }

    private static List<Path> validated(HostEnvironment host, String pathText) {
        var result = new ArrayList<Path>();
        for (var entry : pathText.split(PATH_SEPARATOR)) {
            try {
                var directory = Path.of(entry);
                if (directory.isAbsolute() && host.isDirectory(directory) && !result.contains(directory)) {
                    result.add(directory);
                }
            } catch (InvalidPathException e) {
                // an unusable entry only loses itself
            }
        }
        return List.copyOf(result);
    }
}
