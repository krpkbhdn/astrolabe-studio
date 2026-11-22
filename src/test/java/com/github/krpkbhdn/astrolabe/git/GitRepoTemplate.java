package com.github.krpkbhdn.astrolabe.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.Consumer;

/** A prepared repository built once per test class and copied into each test's own folder. */
final class GitRepoTemplate {

    private final Path root;

    private GitRepoTemplate(Path root) {
        this.root = root;
    }

    static GitRepoTemplate build(String prefix, Consumer<HistoryTestRepo> setup) throws IOException {
        var template = new GitRepoTemplate(Files.createTempDirectory(prefix));
        setup.accept(HistoryTestRepo.init(template.root));
        return template;
    }

    HistoryTestRepo copyInto(Path directory) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var source : (Iterable<Path>) paths::iterator) {
                var target = directory.resolve(root.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return HistoryTestRepo.open(directory);
    }

    static void delete(GitRepoTemplate template) throws IOException {
        if (template == null) {
            return;
        }
        try (var paths = Files.walk(template.root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                path.toFile().delete();
            });
        }
    }
}
