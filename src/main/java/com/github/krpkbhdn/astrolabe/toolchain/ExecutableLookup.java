package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.LoginShellPath;
import com.github.krpkbhdn.astrolabe.workspace.UnixToolDirectories;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Finds a tool binary on PATH and in the user bin directories package managers use, which a GUI-launched app often lacks on PATH. */
@Component
public class ExecutableLookup {

    private static final List<String> WINDOWS_EXTENSIONS = List.of(".exe", ".cmd", ".bat", ".com");

    private final HostEnvironment host;

    private final ManagedToolsDirectory managedTools;

    private final LoginShellPath loginShell;

    @Autowired
    public ExecutableLookup(HostEnvironment host, ManagedToolsDirectory managedTools, LoginShellPath loginShell) {
        this.host = host;
        this.managedTools = managedTools;
        this.loginShell = loginShell;
    }

    public ExecutableLookup(HostEnvironment host, ManagedToolsDirectory managedTools) {
        this(host, managedTools, LoginShellPath.disabled());
    }

    /** PATH directories win; the extra user-writable directories after them are searched only when PATH has no match. */
    public Optional<Path> find(String binary) {
        var names = host.windows() ? WINDOWS_EXTENSIONS.stream().map(ext -> binary + ext).toList() : List.of(binary);
        var directories = new ArrayList<>(host.pathDirectories());
        directories.addAll(extraDirectories());
        for (var directory : directories) {
            for (var name : names) {
                var candidate = directory.resolve(name);
                if (host.isFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** The raw process PATH (unparsable entries such as quoted Windows ones kept verbatim), then the extra lookup folders that exist and are not in it yet; the managed tools/bin is always there so a session started before the first install sees later launchers. */
    public String searchPath() {
        var separator = host.windows() ? ";" : ":";
        var raw = host.env("PATH");
        var entries = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        if (raw != null && !raw.isBlank()) {
            entries.add(raw);
            for (var entry : raw.split(separator)) {
                seen.add(normalized(entry));
            }
        }
        for (var directory : extraDirectories()) {
            if ((directory.equals(managedTools.binDirectory()) || host.isDirectory(directory)) && seen.add(normalized(directory.toString()))) {
                entries.add(directory.toString());
            }
        }
        return String.join(separator, entries);
    }

    private String normalized(String entry) {
        var text = entry.strip();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        if (host.windows()) {
            text = text.replace('/', '\\').toLowerCase(Locale.ROOT);
        }
        while (text.length() > 1 && (text.endsWith("/") || text.endsWith("\\"))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** Environment for child processes: {@link #searchPath} as PATH, and on Windows no implicit current-directory lookup by cmd.exe. */
    public Map<String, String> environment() {
        var environment = new LinkedHashMap<String, String>();
        environment.put("PATH", searchPath());
        if (host.windows()) {
            environment.put("NoDefaultCurrentDirectoryInExePath", "1");
        }
        return environment;
    }

    private List<Path> extraDirectories() {
        var home = host.home();
        var result = new ArrayList<>(List.of(managedTools.binDirectory(), home.resolve("go").resolve("bin"), home.resolve(".cargo").resolve("bin"),
                home.resolve(".dotnet").resolve("tools"), home.resolve(".local").resolve("bin")));
        if (host.windows()) {
            addWindowsDirectories(result);
        } else {
            result.addAll(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/usr/local/go/bin"),
                    home.resolve(".dotnet"), Path.of("/opt/homebrew/opt/openjdk@25/bin"), Path.of("/usr/local/opt/openjdk@25/bin")));
            result.addAll(loginShell.directories(host));
            result.addAll(UnixToolDirectories.of(host));
        }
        return result;
    }

    private void addWindowsDirectories(List<Path> result) {
        addEnvDirectory(result, "APPDATA", "npm");
        for (var relative : List.of("nodejs", "Go/bin", "dotnet", "LLVM/bin")) {
            addEnvDirectory(result, "ProgramFiles", relative);
        }
        addEnvDirectory(result, "USERPROFILE", ".local/bin");
        var pythons = envPath("LOCALAPPDATA", "Programs/Python");
        if (pythons != null) {
            host.childDirectoriesStartingWith(pythons, "Python3").stream()
                    .sorted(Comparator.comparing((Path dir) -> dir.getFileName().toString().length())
                            .thenComparing(dir -> dir.getFileName().toString()).reversed())
                    .forEach(python -> {
                        result.add(python);
                        result.add(python.resolve("Scripts"));
                    });
        }
    }

    private void addEnvDirectory(List<Path> result, String variable, String relative) {
        var directory = envPath(variable, relative);
        if (directory != null) {
            result.add(directory);
        }
    }

    private Path envPath(String variable, String relative) {
        var value = host.env(variable);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).resolve(relative);
        } catch (InvalidPathException e) {
            return null; // an unusable variable only loses this fallback
        }
    }
}
