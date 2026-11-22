package com.github.krpkbhdn.astrolabe.workspace;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bin directories of version managers (nvm, fnm, asdf, mise) that exist; a GUI launch does not see them on PATH. Newest node version first. */
public final class UnixToolDirectories {

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Comparator<Path> NEWEST_FIRST = Comparator.comparing(UnixToolDirectories::versionOf, UnixToolDirectories::compare).reversed();

    private UnixToolDirectories() {
    }

    public static List<Path> of(HostEnvironment host) {
        var home = host.home();
        var result = new ArrayList<Path>();
        for (var node : versioned(host, base(host, "NVM_DIR", home.resolve(".nvm")).resolve("versions").resolve("node"))) {
            result.add(node.resolve("bin"));
        }
        for (var fnm : List.of(base(host, "FNM_DIR", home.resolve(".local").resolve("share").resolve("fnm")), home.resolve(".fnm"))) {
            result.add(fnm.resolve("aliases").resolve("default").resolve("bin"));
            for (var node : versioned(host, fnm.resolve("node-versions"))) {
                result.add(node.resolve("installation").resolve("bin"));
            }
        }
        result.add(base(host, "ASDF_DATA_DIR", home.resolve(".asdf")).resolve("shims"));
        result.add(base(host, "MISE_DATA_DIR", home.resolve(".local").resolve("share").resolve("mise")).resolve("shims"));
        return result.stream().filter(host::isDirectory).toList();
    }

    private static Path base(HostEnvironment host, String variable, Path fallback) {
        var value = host.env(variable);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            var path = Path.of(value);
            return path.isAbsolute() ? path : fallback;
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    private static List<Path> versioned(HostEnvironment host, Path parent) {
        return host.childDirectoriesStartingWith(parent, "").stream().sorted(NEWEST_FIRST).toList();
    }

    private static List<Long> versionOf(Path directory) {
        return NUMBER.matcher(directory.getFileName().toString()).results().map(match -> Long.parseLong(match.group())).toList();
    }

    private static int compare(List<Long> left, List<Long> right) {
        for (var index = 0; index < Math.min(left.size(), right.size()); index++) {
            var order = Long.compare(left.get(index), right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
