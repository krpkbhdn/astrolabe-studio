package com.github.krpkbhdn.ccmanager.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the technologies of a project from tool markers: {@code name} is an exact file name, {@code *.ext} a glob on file names,
 * {@code package.json#react} a package.json listing that key in dependencies, devDependencies or peerDependencies.
 * Scans the root, the common monorepo folders and their direct subfolders only.
 */
@Component
public class TechnologyDetector {

    private static final List<String> MONOREPO_DIRS = List.of("src", "app", "apps", "packages", "frontend", "client", "web");
    private static final Set<String> IGNORED_DIRS = Set.of("node_modules", ".git", "build", "target");
    private static final List<String> DEPENDENCY_SECTIONS = List.of("dependencies", "devDependencies", "peerDependencies");
    private static final int MAX_ENTRIES = 500;
    private static final long MAX_PACKAGE_JSON_BYTES = 1_048_576;
    private static final Comparator<Path> BY_NAME = Comparator.comparing(path -> path.getFileName().toString());

    private final JsonMapper json = JsonMapper.builder().build();

    public Set<String> detect(Path projectDir, Collection<ToolDefinition> tools) {
        var candidates = tools.stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE && !tool.markers().isEmpty()).toList();
        var found = new HashSet<String>();
        for (var folder : foldersToScan(projectDir)) {
            var globNames = new Lazy(folder);
            for (var tool : candidates) {
                if (!found.contains(tool.id()) && tool.markers().stream().anyMatch(marker -> matches(marker, folder, globNames))) {
                    found.add(tool.id());
                }
            }
        }
        for (var tool : candidates) {
            if (found.contains(tool.id()) && tool.kind() == ToolKind.ADD_ON && !tool.baseTool().isEmpty()) {
                found.add(tool.baseTool());
            }
        }
        return found;
    }

    private static List<Path> foldersToScan(Path root) {
        var folders = new ArrayList<Path>(List.of(root));
        for (var name : MONOREPO_DIRS) {
            var dir = root.resolve(name);
            if (isRealDirectory(dir)) {
                folders.add(dir);
                try (Stream<Path> children = Files.list(dir)) {
                    children.sorted(BY_NAME).limit(MAX_ENTRIES)
                            .filter(child -> !IGNORED_DIRS.contains(child.getFileName().toString()) && isRealDirectory(child))
                            .forEach(folders::add);
                } catch (IOException ignored) {
                    // an unreadable folder just contributes nothing
                }
            }
        }
        return folders;
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

    /** File names of a folder for glob markers, read once and only when a glob marker needs them. */
    private static final class Lazy {
        private final Path folder;
        private Set<String> names;

        Lazy(Path folder) {
            this.folder = folder;
        }

        Set<String> get() {
            if (names == null) {
                names = fileNames(folder);
            }
            return names;
        }
    }

    private static Set<String> fileNames(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.sorted(BY_NAME).limit(MAX_ENTRIES).filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private boolean matches(String marker, Path folder, Lazy globNames) {
        var hash = marker.indexOf('#');
        if (hash > 0) {
            var file = marker.substring(0, hash);
            return isRegularFile(folder.resolve(file)) && hasDependency(folder.resolve(file), marker.substring(hash + 1));
        }
        if (marker.startsWith("*.")) {
            var suffix = marker.substring(1).toLowerCase(Locale.ROOT);
            return globNames.get().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).endsWith(suffix));
        }
        return isRegularFile(folder.resolve(marker));
    }

    private boolean hasDependency(Path packageJson, String key) {
        try {
            if (Files.size(packageJson) > MAX_PACKAGE_JSON_BYTES) {
                return false;
            }
            var root = json.readTree(Files.readString(packageJson));
            return DEPENDENCY_SECTIONS.stream().map(root::get).anyMatch(section -> section != null && hasKey(section, key));
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private static boolean hasKey(JsonNode section, String key) {
        return section.isObject() && section.has(key);
    }
}
