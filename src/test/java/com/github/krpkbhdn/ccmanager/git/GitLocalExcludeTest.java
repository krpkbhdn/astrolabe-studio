package com.github.krpkbhdn.ccmanager.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitLocalExcludeTest {

    @TempDir
    Path root;

    private String excludeOf(GitCliSandbox sandbox, Path dir) throws Exception {
        var path = sandbox.runner.out(dir, "rev-parse", "--git-path", "info/exclude").strip();
        var file = dir.resolve(path);
        return Files.exists(file) ? Files.readString(file) : "";
    }

    @Test
    void entryIsAppendedOnceAndIgnoredByGit() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        var exclude = new GitLocalExclude(sandbox.runner);

        exclude.ensureExcluded(sandbox.repo, "CLAUDE.local.md");
        exclude.ensureExcluded(sandbox.repo, "CLAUDE.local.md", "notes/other.txt");

        var text = excludeOf(sandbox, sandbox.repo);
        assertThat(text).contains("/CLAUDE.local.md\n").contains("/notes/other.txt\n");
        assertThat(text.split("/CLAUDE.local.md", -1)).hasSize(2);
    }

    @Test
    void linkedWorktreeUsesTheSharedExcludeFile() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        sandbox.write("README.md", "x\n");
        sandbox.commitAll("initial");
        var linked = root.resolve("linked");
        sandbox.git("worktree", "add", "-b", "other", linked.toString());

        new GitLocalExclude(sandbox.runner).ensureExcluded(linked, "CLAUDE.local.md");

        assertThat(excludeOf(sandbox, sandbox.repo)).contains("/CLAUDE.local.md");
        Files.writeString(linked.resolve("CLAUDE.local.md"), "mine\n");
        assertThat(sandbox.runner.out(linked, "status", "--porcelain")).doesNotContain("CLAUDE.local.md");
    }

    @Test
    void pathAlreadyIgnoredIsNotAdded() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        sandbox.write(".gitignore", "CLAUDE.local.md\n");

        new GitLocalExclude(sandbox.runner).ensureExcluded(sandbox.repo, "CLAUDE.local.md");

        assertThat(excludeOf(sandbox, sandbox.repo)).doesNotContain("CLAUDE.local.md");
    }

    @Test
    void folderOutsideARepositoryIsSkippedSilently() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        var plain = Files.createDirectory(root.resolve("plain"));

        new GitLocalExclude(sandbox.runner).ensureExcluded(plain, "CLAUDE.local.md");

        assertThat(Files.list(plain)).isEmpty();
    }

    @Test
    void subfolderOfARepositoryGetsAnEntryThatMatches() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        var sub = Files.createDirectories(sandbox.repo.resolve("apps/web"));

        new GitLocalExclude(sandbox.runner).ensureExcluded(sub, "CLAUDE.local.md");

        assertThat(excludeOf(sandbox, sub)).contains("/apps/web/CLAUDE.local.md\n");
        Files.writeString(sub.resolve("CLAUDE.local.md"), "mine\n");
        assertThat(sandbox.runner.out(sub, "check-ignore", "CLAUDE.local.md")).contains("CLAUDE.local.md");
    }

    @Test
    void unsafePathsAreRejected() throws Exception {
        var sandbox = GitCliSandbox.create(root);
        var exclude = new GitLocalExclude(sandbox.runner);

        for (var bad : new String[] {"a\nb", "a\rb", "../x", "a/../x", "/abs", "C:/x", "!x", "#x", "*.md", "a?", "a[1]", "a\\b"}) {
            assertThatThrownBy(() -> exclude.ensureExcluded(sandbox.repo, bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
