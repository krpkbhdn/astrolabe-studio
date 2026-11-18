package com.github.krpkbhdn.astrolabe.git;

import com.github.krpkbhdn.astrolabe.TestProcesses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
/** A throw-away repository driven by the real git CLI, with a repo-local identity and no signing. */
final class HistoryTestRepo {

    private final Path root;

    private HistoryTestRepo(Path root) {
        this.root = root;
    }

    private static final class GitProbe {
        static final boolean AVAILABLE = probe();

        private static boolean probe() {
            try {
                return TestProcesses.run(new ProcessBuilder("git", "--version").start(), Duration.ofSeconds(10)).exitCode() == 0;
            } catch (IOException | AssertionError e) {
                return false;
            }
        }
    }

    static boolean gitAvailable() {
        return GitProbe.AVAILABLE;
    }

    static HistoryTestRepo init(Path root) {
        var repo = new HistoryTestRepo(root);
        repo.git("init", "-q", "-b", "main");
        try {
            Files.writeString(root.resolve(".git").resolve("config"), """
                    [user]
                    \tname = Test User
                    \temail = test@example.com
                    [commit]
                    \tgpgsign = false
                    [tag]
                    \tgpgsign = false
                    [core]
                    \tautocrlf = false
                    """, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return repo;
    }

    Path root() {
        return root;
    }

    String git(String... args) {
        try {
            var process = TestProcesses.git(args).directory(root.toFile()).redirectErrorStream(true).start();
            var drained = TestProcesses.run(process);
            if (drained.exitCode() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + drained.stdout());
            }
            return drained.stdout().strip();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void write(String file, String content) {
        try {
            var path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String read(String file) {
        try {
            return Files.readString(root.resolve(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String commit(String file, String content, String message) {
        write(file, content);
        git("add", "--", file);
        git("commit", "-q", "-m", message);
        return git("rev-parse", "HEAD");
    }
}
