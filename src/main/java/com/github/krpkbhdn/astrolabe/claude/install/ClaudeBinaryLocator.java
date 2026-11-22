package com.github.krpkbhdn.astrolabe.claude.install;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.LoginShellPath;
import com.github.krpkbhdn.astrolabe.workspace.UnixToolDirectories;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Where claude may live: PATH, native installer, package-manager bins; any claude.exe beats a .cmd shim. */
@Component
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeBinaryLocator {

    private static final String APPDATA = "APPDATA";
    private static final String LOCALAPPDATA = "LOCALAPPDATA";
    private static final String VOLTA_HOME = "VOLTA_HOME";
    private static final String SCOOP = "SCOOP";
    private static final String PNPM_HOME = "PNPM_HOME";
    private static final String BUN_INSTALL = "BUN_INSTALL";
    private static final List<String> WINDOWS_NAMES = List.of("claude.exe", "claude.cmd", "claude.bat");
    private static final List<String> UNIX_NAMES = List.of("claude");
    private static final Path NPM_SHIM_EXE =
            Path.of("node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe");

    private final ClaudeProperties properties;
    private final LoginShellPath loginShell;

    @Autowired
    public ClaudeBinaryLocator(ClaudeProperties properties, LoginShellPath loginShell) {
        this.properties = properties;
        this.loginShell = loginShell;
    }

    public ClaudeBinaryLocator() {
        this(new ClaudeProperties(""), LoginShellPath.disabled());
    }

    /** Forgets the cached login-shell PATH so the next lookup asks the shell again. */
    public void refresh() {
        loginShell.invalidate();
    }

    /**
     * Unix child-process environment for running claude: PATH starts with the folder holding the binary (an npm shim's
     * {@code #!/usr/bin/env node} finds the node installed beside it), then the process PATH and the login-shell PATH.
     * Empty on Windows.
     */
    public Map<String, String> environmentFor(HostEnvironment host, Path executable) {
        if (host.windows() || executable.getParent() == null) {
            return Map.of();
        }
        var entries = new ArrayList<String>();
        entries.add(executable.getParent().toString());
        var raw = host.env("PATH");
        if (raw != null && !raw.isBlank()) {
            entries.add(raw);
        }
        loginShell.directories(host).forEach(directory -> entries.add(directory.toString()));
        return Map.of("PATH", String.join(":", entries));
    }

    public Optional<Path> find(HostEnvironment host) {
        return candidates(host).stream().filter(host::isFile).findFirst().map(found -> preferNpmExe(host, found));
    }

    /** The npm claude.cmd shim is replaced by the real exe beside it, which has no batch-file argument limits. */
    private static Path preferNpmExe(HostEnvironment host, Path found) {
        if (!host.windows() || !found.getFileName().toString().equalsIgnoreCase("claude.cmd")) {
            return found;
        }
        var exe = found.resolveSibling(NPM_SHIM_EXE);
        return host.isFile(exe) ? exe : found;
    }

    /** Lookup order; a GUI-launched process may have a reduced PATH, so fallbacks matter. */
    public List<Path> candidates(HostEnvironment host) {
        var names = host.windows() ? WINDOWS_NAMES : UNIX_NAMES;
        var nativeDirectory = host.home().resolve(".local").resolve("bin");
        var result = new ArrayList<Path>();
        var override = overridePath();
        override.ifPresent(result::add);
        var fallbacks = new ArrayList<>(loginShell.directories(host));
        fallbacks.addAll(fallbackDirectories(host));
        for (var name : names) {
            result.addAll(host.pathCandidates(List.of(name)));
            result.add(nativeDirectory.resolve(name));
            fallbacks.forEach(dir -> result.add(dir.resolve(name)));
        }
        return result.stream().distinct().toList();
    }

    private Optional<Path> overridePath() {
        var configured = properties.path();
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        try {
            var path = Path.of(configured.strip());
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static List<Path> fallbackDirectories(HostEnvironment host) {
        return host.windows() ? windowsFallbacks(host) : unixFallbacks(host);
    }

    private static List<Path> windowsFallbacks(HostEnvironment host) {
        var home = host.home();
        var result = new ArrayList<Path>();
        addEnvDirectory(result, host, APPDATA, "npm");
        addEnvDirectory(result, host, LOCALAPPDATA, "Microsoft", "WinGet", "Links");
        addEnvDirectory(result, host, VOLTA_HOME, "bin");
        addEnvDirectory(result, host, LOCALAPPDATA, "Volta", "bin");
        addEnvDirectory(result, host, SCOOP, "shims");
        result.add(home.resolve("scoop").resolve("shims"));
        addEnvDirectory(result, host, PNPM_HOME);
        addEnvDirectory(result, host, LOCALAPPDATA, "pnpm");
        addEnvDirectory(result, host, BUN_INSTALL, "bin");
        result.add(home.resolve(".bun").resolve("bin"));
        addEnvDirectory(result, host, LOCALAPPDATA, "Yarn", "bin");
        return result;
    }

    private static List<Path> unixFallbacks(HostEnvironment host) {
        var home = host.home();
        var result = new ArrayList<Path>();
        result.add(Path.of("/usr/local/bin"));
        result.add(Path.of("/opt/homebrew/bin"));
        result.add(home.resolve(".npm-global").resolve("bin"));
        result.add(home.resolve(".volta").resolve("bin"));
        result.add(home.resolve(".bun").resolve("bin"));
        result.add(home.resolve(".yarn").resolve("bin"));
        addEnvDirectory(result, host, PNPM_HOME);
        result.add(home.resolve(".local").resolve("share").resolve("pnpm"));
        result.add(Path.of("/home/linuxbrew/.linuxbrew/bin"));
        result.addAll(UnixToolDirectories.of(host));
        return result;
    }

    private static void addEnvDirectory(List<Path> result, HostEnvironment host, String variable, String... children) {
        var value = host.env(variable);
        if (value == null || value.isBlank()) {
            return;
        }
        Path directory;
        try {
            directory = Path.of(value);
        } catch (InvalidPathException e) {
            return;
        }
        if (!directory.isAbsolute()) {
            return;
        }
        for (var child : children) {
            directory = directory.resolve(child);
        }
        result.add(directory);
    }
}
