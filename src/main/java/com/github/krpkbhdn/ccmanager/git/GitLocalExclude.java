package com.github.krpkbhdn.ccmanager.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Keeps user-only files out of git through the repository's local exclude file, which is never committed or shared. */
@Service
public class GitLocalExclude {

    private static final Pattern UNSAFE = Pattern.compile("[\\r\\n*?\\[\\\\:]");

    private final GitRunner git;

    public GitLocalExclude(GitRunner git) {
        this.git = git;
    }

    /**
     * Adds each path (relative to {@code projectDir}, which may be a subfolder of the repository) unless git already ignores it;
     * does nothing outside a git repository.
     *
     * @throws IllegalArgumentException for a path that is absolute, climbs out, starts with {@code !} or {@code #} or holds a line break or glob character
     */
    public void ensureExcluded(Path projectDir, String... relativePaths) throws IOException {
        for (var path : relativePaths) {
            requireLiteralRelative(path);
        }
        var gitPath = git.tryRun(projectDir, "rev-parse", "--git-path", "info/exclude");
        if (!gitPath.ok() || gitPath.stdout().isBlank()) {
            return;
        }
        var exclude = projectDir.resolve(gitPath.stdout().strip());
        var prefix = git.tryRun(projectDir, "rev-parse", "--show-prefix");
        var existing = Files.exists(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
        var lines = existing.lines().map(String::strip).toList();
        var added = new StringBuilder();
        for (var path : relativePaths) {
            var entry = "/" + (prefix.ok() ? prefix.stdout().strip() : "") + path;
            if (!lines.contains(entry) && !git.tryRun(projectDir, "check-ignore", "-q", "--", path).ok()) {
                added.append(entry).append('\n');
            }
        }
        if (added.isEmpty()) {
            return;
        }
        Files.createDirectories(exclude.getParent());
        var needsBreak = !existing.isEmpty() && !existing.endsWith("\n");
        Files.writeString(exclude, (needsBreak ? "\n" : "") + added, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void requireLiteralRelative(String path) {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("!") || path.startsWith("#")
                || UNSAFE.matcher(path).find() || List.of(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("Not a safe relative exclude path");
        }
    }
}
