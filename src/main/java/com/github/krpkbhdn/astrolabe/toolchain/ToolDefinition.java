package com.github.krpkbhdn.astrolabe.toolchain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One catalog entry; includes names technologies the tool covers without an entry of their own (shown as a note);install keys are windows, linux, macos, each an argv list; manualInstall is one display-only command line per OS, never run. */
public record ToolDefinition(String id, String name, String group, ToolKind kind, String binary,
                             List<String> versionArgs, String lspPlugin, String prerequisite,
                             List<String> markers, Map<String, List<String>> install, String docsUrl,
                             Map<String, String> manualInstall, Map<String, DownloadSource> download,
                             List<String> includes) {

    public ToolDefinition(String id, String name, String group, ToolKind kind, String binary,
                          List<String> versionArgs, String lspPlugin, String prerequisite,
                          List<String> markers, Map<String, List<String>> install, String docsUrl,
                          Map<String, String> manualInstall, Map<String, DownloadSource> download) {
        this(id, name, group, kind, binary, versionArgs, lspPlugin, prerequisite, markers, install, docsUrl,
                manualInstall, download, null);
    }

    public ToolDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(group);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(binary);
        versionArgs = versionArgs == null ? List.of() : List.copyOf(versionArgs);
        lspPlugin = lspPlugin == null ? "" : lspPlugin;
        prerequisite = prerequisite == null ? "" : prerequisite;
        markers = markers == null ? List.of() : List.copyOf(markers);
        docsUrl = docsUrl == null ? "" : docsUrl;
        Map<String, List<String>> copy = new HashMap<>();
        if (install != null) {
            install.forEach((os, argv) -> copy.put(os, List.copyOf(argv)));
        }
        install = Map.copyOf(copy);
        Map<String, String> manual = new HashMap<>();
        if (manualInstall != null) {
            manualInstall.forEach((os, line) -> {
                if (line != null && !line.isBlank()) {
                    manual.put(os, line);
                }
            });
        }
        manualInstall = Map.copyOf(manual);
        download = download == null ? Map.of() : Map.copyOf(download);
        includes = includes == null ? List.of() : List.copyOf(includes);
        if (includes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("includes of " + id + " must not contain blank names");
        }
    }

    public Optional<DownloadSource> downloadSource(String os) {
        return Optional.ofNullable(download.get(os));
    }

    public boolean supportsChannels() {
        return download.values().stream().anyMatch(source -> source.pinnedVersion().isPresent());
    }

    public List<String> installCommand(String os) {
        return install.getOrDefault(os, List.of());
    }

    public String manualCommand(String os) {
        return manualInstall.getOrDefault(os, "");
    }

    public boolean hasInstallCommand(String os) {
        return !installCommand(os).isEmpty();
    }
}
