package com.github.krpkbhdn.ccmanager.preset.apply;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** The prompt of the interactive "Tidy with Claude" session that merges duplicate rules after a preset was applied. */
public final class TidyClaudeMd {

    private static final String RESOURCE = "/preset/tidy-claude-md.md";

    private TidyClaudeMd() {
    }

    public static String prompt() {
        try (var in = TidyClaudeMd.class.getResourceAsStream(RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Blocking; there is nothing to tidy without CLAUDE.md or CLAUDE.local.md. */
    public static boolean applicableTo(Path projectDir) {
        return Stream.of("CLAUDE.md", "CLAUDE.local.md").anyMatch(name -> Files.isRegularFile(projectDir.resolve(name)));
    }
}
