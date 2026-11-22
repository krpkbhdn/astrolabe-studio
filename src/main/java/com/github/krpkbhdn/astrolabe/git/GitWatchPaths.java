package com.github.krpkbhdn.astrolabe.git;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Path rules and directory scan of {@link GitWatcher}: which changes matter and which directories get a watch. */
final class GitWatchPaths {

    enum Zone { GIT_ROOT, GIT_REFS, TREE }

    static final Set<String> IGNORED_TREE_NAMES = Set.of(".git", "node_modules", "build", "target", ".gradle", "dist",
            ".idea", "out", ".venv", "__pycache__", ".next");
    static final Set<String> GIT_STATE_NAMES = Set.of("HEAD", "ORIG_HEAD", "index", "MERGE_HEAD", "CHERRY_PICK_HEAD",
            "REVERT_HEAD", "rebase-merge", "rebase-apply", "packed-refs", "refs");
    static final String LOCK_SUFFIX = ".lock";
    private static final Logger log = LoggerFactory.getLogger(GitWatchPaths.class);

    private GitWatchPaths() {}

    /** Windows reports a MODIFY for every ancestor folder of a changed file, even when that file sits in an ignored folder. */
    static boolean isDirectoryTimestampNoise(Zone zone, WatchEvent.Kind<?> kind, Path path) {
        return zone == Zone.TREE && kind == ENTRY_MODIFY && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isRelevant(Zone zone, Path name) {
        var text = name.toString();
        if (text.endsWith(LOCK_SUFFIX)) {
            return false;
        }
        return switch (zone) {
            case GIT_ROOT -> GIT_STATE_NAMES.contains(text);
            case GIT_REFS -> true;
            case TREE -> StreamSupport.stream(name.spliterator(), false).noneMatch(segment -> IGNORED_TREE_NAMES.contains(segment.toString()));
        };
    }

    static List<Path> walkDirectories(Path start, Zone zone, int limit) {
        var directories = new ArrayList<Path>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(start) && zone == Zone.TREE && IGNORED_TREE_NAMES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    directories.add(dir);
                    return directories.size() > limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("walking {} failed: {}", start, e.getMessage());
        }
        return directories;
    }
}
