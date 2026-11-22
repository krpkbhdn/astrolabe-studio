package com.github.krpkbhdn.astrolabe.git;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.git.GitWatchPaths.Zone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWatchPathsTest {

    @TempDir
    Path dir;

    @Test
    void lockFilesAreNeverRelevant() {
        assertThat(GitWatchPaths.isRelevant(Zone.GIT_ROOT, Path.of("index.lock"))).isFalse();
        assertThat(GitWatchPaths.isRelevant(Zone.GIT_REFS, Path.of("heads", "main.lock"))).isFalse();
        assertThat(GitWatchPaths.isRelevant(Zone.TREE, Path.of("src", "A.java.lock"))).isFalse();
    }

    @Test
    void gitRootOnlyReactsToStateNames() {
        assertThat(GitWatchPaths.isRelevant(Zone.GIT_ROOT, Path.of("HEAD"))).isTrue();
        assertThat(GitWatchPaths.isRelevant(Zone.GIT_ROOT, Path.of("config"))).isFalse();
        assertThat(GitWatchPaths.isRelevant(Zone.GIT_REFS, Path.of("heads", "main"))).isTrue();
    }

    @Test
    void treeIgnoresAnySegmentInIgnoredNames() {
        assertThat(GitWatchPaths.isRelevant(Zone.TREE, Path.of("src", "Main.java"))).isTrue();
        assertThat(GitWatchPaths.isRelevant(Zone.TREE, Path.of("node_modules", "x", "y.js"))).isFalse();
        assertThat(GitWatchPaths.isRelevant(Zone.TREE, Path.of("a", "build", "out.txt"))).isFalse();
    }

    @Test
    void directoryModifyInTreeIsTimestampNoise() throws IOException {
        var sub = Files.createDirectory(dir.resolve("sub"));
        var file = Files.createFile(dir.resolve("f.txt"));
        assertThat(GitWatchPaths.isDirectoryTimestampNoise(Zone.TREE, ENTRY_MODIFY, sub)).isTrue();
        assertThat(GitWatchPaths.isDirectoryTimestampNoise(Zone.TREE, ENTRY_MODIFY, file)).isFalse();
        assertThat(GitWatchPaths.isDirectoryTimestampNoise(Zone.TREE, ENTRY_CREATE, sub)).isFalse();
        assertThat(GitWatchPaths.isDirectoryTimestampNoise(Zone.GIT_REFS, ENTRY_MODIFY, sub)).isFalse();
    }

    @Test
    void walkSkipsIgnoredDirectoriesOnlyInTreeZone() throws IOException {
        Files.createDirectories(dir.resolve("src/inner"));
        Files.createDirectories(dir.resolve("node_modules/pkg"));

        assertThat(GitWatchPaths.walkDirectories(dir, Zone.TREE, 100))
                .containsExactlyInAnyOrder(dir, dir.resolve("src"), dir.resolve("src/inner"));
        assertThat(GitWatchPaths.walkDirectories(dir, Zone.GIT_REFS, 100))
                .contains(dir.resolve("node_modules"), dir.resolve("node_modules/pkg"));
    }

    @Test
    void walkStopsOnceLimitIsExceeded() throws IOException {
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.createDirectories(dir.resolve("c"));

        assertThat(GitWatchPaths.walkDirectories(dir, Zone.TREE, 2)).hasSize(3);
    }
}
