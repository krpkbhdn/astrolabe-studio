package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Writes an instruction as a managed block in the project's overlay instructions.md (outside the repository), never in CLAUDE.md. */
@Component
class InstructionApplier implements PresetComponentApplier {

    private static final String OVERLAY_FILE = "instructions.md";

    private final ProjectOverlaySettings overlays;

    InstructionApplier(ProjectOverlaySettings overlays) {
        this.overlays = overlays;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.INSTRUCTION;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) throws IOException {
        var plan = ClaudeMdSections.plan(OVERLAY_FILE, read(overlays.overlayOf(projectDir)), component.name(), markdown(component));
        var change = PlannedChange.of(component, plan.action(), plan.detail());
        return plan.text() == null ? change : change.withPreview(ClaudeMdSections.block(component.name(), markdown(component)));
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) throws IOException {
        var overlay = overlays.overlayOf(projectDir);
        var plan = ClaudeMdSections.plan(OVERLAY_FILE, read(overlay), component.name(), markdown(component));
        if (plan.action() == ChangeAction.CONFLICT) {
            return ComponentOutcome.of(component, OutcomeStatus.SKIPPED, plan.detail());
        }
        if (plan.text() != null) {
            overlay.write(overlay.instructionsFile(), plan.text());
        }
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, plan.detail());
    }

    private static String read(ProjectOverlay overlay) throws IOException {
        return Files.isRegularFile(overlay.instructionsFile()) ? Files.readString(overlay.instructionsFile()) : "";
    }

    private static String markdown(PresetComponent component) {
        return component.payload().path("markdown").asString("");
    }
}
