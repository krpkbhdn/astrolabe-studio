package com.github.krpkbhdn.astrolabe.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the technologies of a project from tool markers: {@code name} is an exact file name, {@code *.ext} a glob on file names,
 * {@code package.json#react} a package.json listing that key in dependencies, devDependencies or peerDependencies.
 * Walks the project breadth-first (bounded by depth and folder count), skipping dependency, build and hidden folders.
 */
@Component
public class TechnologyDetector {

    private static final Set<String> IGNORED_DIRS = Set.of("node_modules", ".git", "build", "target", "dist", "out", ".gradle",
            ".idea", ".vscode", ".venv", "venv", "__pycache__", ".next", ".angular", "coverage", "vendor");
    private static final List<String> DEPENDENCY_SECTIONS = List.of("dependencies", "devDependencies", "peerDependencies");
    private static final long MAX_PACKAGE_JSON_BYTES = 1_048_576;
    private static final Comparator<Path> BY_NAME = Comparator.comparing(path -> path.getFileName().toString());

    private final JsonMapper json = JsonMapper.builder().build();
    private final ToolchainProperties properties;

    public TechnologyDetector(ToolchainProperties properties) {
        this.properties = properties;
    }

    private record Level(Path folder, int depth) {
    }

    public Set<String> detect(Path projectDir, Collection<ToolDefinition> tools) {
        var markersById = new HashMap<String, List<String>>();
        tools.stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE && !tool.markers().isEmpty())
                .forEach(tool -> markersById.put(tool.id(), tool.markers()));
        return detectMarked(projectDir, markersById);
    }

    /** Same bounded scan for a caller-given table of name to markers; returns the names with a marker found. */
    public Set<String> detectMarked(Path projectDir, Map<String, List<String>> markersByName) {
        var candidates = markersByName.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).toList();
        var found = new HashSet<String>();
        var queue = new ArrayDeque<Level>();
        queue.add(new Level(projectDir, 0));
        var queued = 1;
        while (!queue.isEmpty() && found.size() < candidates.size()) {
            var level = queue.poll();
            var folder = level.folder();
            var contents = new FolderContents(folder);
            for (var tool : candidates) {
                if (!found.contains(tool.getKey()) && tool.getValue().stream().anyMatch(marker -> matches(marker, folder, contents))) {
                    found.add(tool.getKey());
                }
            }
            if (level.depth() < properties.maxScanDepth() && queued < properties.maxScanFolders()) {
                for (var child : childFolders(folder)) {
                    if (queued >= properties.maxScanFolders()) {
                        break;
                    }
                    queue.add(new Level(child, level.depth() + 1));
                    queued++;
                }
            }
        }
        return found;
    }

    private List<Path> childFolders(Path folder) {
        try (Stream<Path> children = Files.list(folder)) {
            // a folder above the cap is cut in directory order, so which entries stay is not deterministic there
            return children.limit(properties.maxFolderEntries()).sorted(BY_NAME).filter(TechnologyDetector::isScannable).toList();
        } catch (IOException | java.io.UncheckedIOException | java.nio.file.DirectoryIteratorException e) {
            return List.of();
        }
    }

    private static boolean isScannable(Path child) {
        var name = child.getFileName().toString();
        return !IGNORED_DIRS.contains(name) && !name.startsWith(".") && isRealDirectory(child);
    }

    private static boolean isRealDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            // a Windows junction is neither a symlink nor a regular directory
            return !Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** What one folder offers to markers, read once and only when a marker needs it: file names for globs, dependency sets per package.json. */
    private final class FolderContents {
        private final Path folder;
        private Set<String> names;
        private final Map<String, Set<String>> dependencies = new HashMap<>();

        FolderContents(Path folder) {
            this.folder = folder;
        }

        Set<String> fileNames() {
            if (names == null) {
                names = readFileNames(folder);
            }
            return names;
        }

        Set<String> dependenciesOf(String file) {
            return dependencies.computeIfAbsent(file, name -> readDependencies(folder.resolve(name)));
        }
    }

    private Set<String> readFileNames(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.limit(properties.maxFolderEntries()).filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        } catch (IOException | java.io.UncheckedIOException | java.nio.file.DirectoryIteratorException e) {
            return Set.of();
        }
    }

    private boolean matches(String marker, Path folder, FolderContents contents) {
        var hash = marker.indexOf('#');
        if (hash > 0) {
            var file = marker.substring(0, hash);
            return isRegularFile(folder.resolve(file)) && contents.dependenciesOf(file).contains(marker.substring(hash + 1));
        }
        if (marker.startsWith("*.")) {
            var suffix = marker.substring(1).toLowerCase(Locale.ROOT);
            return contents.fileNames().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).endsWith(suffix));
        }
        return isRegularFile(folder.resolve(marker));
    }

    private Set<String> readDependencies(Path packageJson) {
        try {
            if (Files.size(packageJson) > MAX_PACKAGE_JSON_BYTES) {
                return Set.of();
            }
            var root = json.readTree(Files.readString(packageJson));
            var keys = new HashSet<String>();
            for (var section : DEPENDENCY_SECTIONS) {
                var node = root.get(section);
                if (node != null && node.isObject()) {
                    keys.addAll(node.propertyNames());
                }
            }
            return keys;
        } catch (RuntimeException | IOException e) {
            return Set.of();
        }
    }
}
