package com.github.krpkbhdn.ccmanager.toolchain.managed;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Layout of tools the app downloads itself: {@code <dataDir>/tools/<id>/<version>/}, {@code tools/bin} launchers, {@code tools/<id>/state}. */
@Component
public class ManagedToolsDirectory {

    static final String COMPLETE_MARKER = ".ccm-complete";
    private static final Logger log = LoggerFactory.getLogger(ManagedToolsDirectory.class);
    private static final Pattern TOOL_ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.-]{0,63}");
    private static final Pattern VERSION_PART = Pattern.compile("\\d+|\\D+");
    private static final String STATE = "state";

    private final Path root;

    public ManagedToolsDirectory(AppProperties app) {
        this.root = app.dataDir().resolve("tools").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path binDirectory() {
        return root.resolve("bin");
    }

    public Path downloadsDirectory() {
        return root.resolve(".downloads");
    }

    public Path versionDirectory(String id, String version) {
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid tool version: " + version);
        }
        return toolDirectory(id).resolve(version);
    }

    public Path stateDirectory(String id) {
        return toolDirectory(id).resolve(STATE);
    }

    /** The newest version folder that holds the completion marker; partial folders are ignored. */
    public Optional<String> installedVersion(String id) {
        var tool = toolDirectory(id);
        if (!Files.isDirectory(tool)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(tool)) {
            return children.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(COMPLETE_MARKER)))
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> VERSION.matcher(name).matches())
                    .max(ManagedToolsDirectory::compareVersions);
        } catch (IOException e) {
            log.warn("Cannot list installed versions of {}: {}", id, e.toString());
            return Optional.empty();
        }
    }

    public void markComplete(Path versionDirectory) throws IOException {
        Files.writeString(versionDirectory.resolve(COMPLETE_MARKER), "");
    }

    /** Best effort: a file locked on Windows leaves its folder for the next install. */
    public void deleteOtherVersions(String id, String keep) {
        var tool = toolDirectory(id);
        if (!Files.isDirectory(tool)) {
            return;
        }
        try (Stream<Path> children = Files.list(tool)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals(keep) && !dir.getFileName().toString().equals(STATE))
                    .forEach(dir -> deleteTree(dir));
        } catch (IOException e) {
            log.warn("Cannot list versions of {}: {}", id, e.toString());
        }
    }

    /** Creates {@code tools/bin} so it can be on PATH before the first install; a failure only means the first install creates it. */
    @PostConstruct
    public void prepareBinDirectory() {
        try {
            Files.createDirectories(binDirectory());
        } catch (IOException e) {
            log.warn("Cannot create {}: {}", binDirectory(), e.toString());
        }
    }

    /** Removes stale download parts and unfinished version folders left by a crashed or cancelled install. */
    public void sweepStale(String id) {
        var downloads = downloadsDirectory();
        if (Files.isDirectory(downloads)) {
            try (Stream<Path> parts = Files.list(downloads)) {
                parts.filter(part -> part.getFileName().toString().endsWith(".part")).forEach(ManagedToolsDirectory::deleteTree);
            } catch (IOException e) {
                log.warn("Cannot list {}: {}", downloads, e.toString());
            }
        }
        var tool = toolDirectory(id);
        if (Files.isDirectory(tool)) {
            try (Stream<Path> children = Files.list(tool)) {
                children.filter(child -> child.getFileName().toString().contains(".partial-")).forEach(ManagedToolsDirectory::deleteTree);
            } catch (IOException e) {
                log.warn("Cannot list {}: {}", tool, e.toString());
            }
        }
    }

    /** Removes the launchers named by {@code launcherCommands} first (so no new session starts the tool), then {@code tools/<id>}; returns what stayed behind. */
    public List<Path> deleteTool(String id, Collection<String> launcherCommands) {
        var leftovers = new ArrayList<Path>();
        for (var command : launcherCommands) {
            for (var suffix : new String[] {".cmd", ""}) {
                var launcher = binDirectory().resolve(requireTool(command) + suffix);
                if (!deleteQuietly(launcher)) {
                    leftovers.add(launcher);
                }
            }
        }
        leftovers.addAll(deleteTreeLeftovers(toolDirectory(id)));
        return leftovers;
    }

    private Path toolDirectory(String id) {
        return root.resolve(requireTool(id));
    }

    private static String requireTool(String id) {
        if (!TOOL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid tool id: " + id);
        }
        return id;
    }

    private static boolean deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", path, e.toString());
            return false;
        }
    }

    static boolean deleteTree(Path directory) {
        return deleteTreeLeftovers(directory).isEmpty();
    }

    private static List<Path> deleteTreeLeftovers(Path directory) {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.sorted(Comparator.reverseOrder()).filter(path -> !deleteQuietly(path)).toList();
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", directory, e.toString());
            return List.of(directory);
        }
    }

    /** Digit runs compare as numbers, other runs as text. */
    static int compareVersions(String a, String b) {
        var left = VERSION_PART.matcher(a).results().map(r -> r.group()).toList();
        var right = VERSION_PART.matcher(b).results().map(r -> r.group()).toList();
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            var x = left.get(i);
            var y = right.get(i);
            int order = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                    ? new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y))
                    : x.compareTo(y);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
