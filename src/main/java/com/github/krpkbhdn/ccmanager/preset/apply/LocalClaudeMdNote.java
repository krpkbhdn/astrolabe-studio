package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.git.GitLocalExclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keeps one managed note in the project's CLAUDE.local.md (kept out of git); the note is a hint, so a failure is logged and never thrown. */
@Component
public final class LocalClaudeMdNote {

    private static final Logger log = LoggerFactory.getLogger(LocalClaudeMdNote.class);

    private final GitLocalExclude localExclude;

    public LocalClaudeMdNote(GitLocalExclude localExclude) {
        this.localExclude = localExclude;
    }

    /** Creates or updates the block of {@code title}; a hand-written section of that title or broken markers leave the file untouched. */
    public void write(Path projectDir, String title, String body) {
        try {
            var file = ProjectFiles.resolve(projectDir, LocalConfigFiles.CLAUDE_MD);
            var seen = ProjectFiles.read(file);
            var plan = ClaudeMdSections.plan(LocalConfigFiles.CLAUDE_MD, seen, title, body);
            if (plan.action() == ChangeAction.CONFLICT) {
                log.debug("Note '{}' not written: {}", title, plan.detail());
                return;
            }
            localExclude.ensureExcluded(projectDir, LocalConfigFiles.CLAUDE_MD);
            if (plan.text() != null) {
                ProjectFiles.replaceIfUnchanged(file, seen, plan.text());
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not write the note '{}' to {}: {}", title, LocalConfigFiles.CLAUDE_MD, e.getClass().getSimpleName());
        }
    }

    /** Removes the block of {@code title} and deletes the file when nothing else is left in it. */
    public void remove(Path projectDir, String title) {
        try {
            var file = ProjectFiles.resolve(projectDir, LocalConfigFiles.CLAUDE_MD);
            var seen = ProjectFiles.read(file);
            var without = ClaudeMdSections.remove(seen, title);
            if (without.equals(seen)) {
                return;
            }
            if (without.isBlank()) {
                Files.deleteIfExists(file);
            } else {
                ProjectFiles.replaceIfUnchanged(file, seen, without);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not remove the note '{}' from {}: {}", title, LocalConfigFiles.CLAUDE_MD, e.getClass().getSimpleName());
        }
    }
}
