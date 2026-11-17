package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keeps one managed note in the project's overlay instructions (outside the repository); the note is a hint, so a failure is logged and never thrown. */
@Component
public final class LocalClaudeMdNote {

    private static final Logger log = LoggerFactory.getLogger(LocalClaudeMdNote.class);
    private static final String FILE_NAME = "instructions.md";

    private final ProjectOverlaySettings overlays;

    public LocalClaudeMdNote(ProjectOverlaySettings overlays) {
        this.overlays = overlays;
    }

    /** Creates or updates the block of {@code title}; a hand-written section of that title or broken markers leave the file untouched. */
    public void write(Path projectDir, String title, String body) {
        try {
            var overlay = overlays.overlayOf(projectDir);
            var file = overlay.instructionsFile();
            var seen = ProjectFiles.read(file);
            var plan = ClaudeMdSections.plan(FILE_NAME, seen, title, body);
            if (plan.action() == ChangeAction.CONFLICT) {
                log.debug("Note '{}' not written: {}", title, plan.detail());
            } else if (plan.text() != null) {
                overlay.write(file, plan.text());
            }
        } catch (IOException | IllegalArgumentException | ExtensionException e) {
            log.warn("Could not write the note '{}' to the project instructions: {}", title, e.getClass().getSimpleName());
        }
    }

    /** Removes the block of {@code title} and deletes the file when nothing else is left in it. */
    public void remove(Path projectDir, String title) {
        try {
            var overlay = overlays.overlayOf(projectDir);
            var file = overlay.instructionsFile();
            var seen = ProjectFiles.read(file);
            var without = ClaudeMdSections.remove(seen, title);
            if (without.equals(seen)) {
                return;
            }
            if (without.isBlank()) {
                Files.deleteIfExists(file);
            } else {
                overlay.write(file, without);
            }
        } catch (IOException | IllegalArgumentException | ExtensionException e) {
            log.warn("Could not remove the note '{}' from the project instructions: {}", title, e.getClass().getSimpleName());
        }
    }
}
