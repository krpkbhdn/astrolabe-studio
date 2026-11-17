package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** An agent is a markdown file in the agents folder of the project's overlay (outside the repository); an existing one is never changed. */
@Component
class AgentApplier implements PresetComponentApplier {

    private final ProjectOverlaySettings overlays;

    AgentApplier(ProjectOverlaySettings overlays) {
        this.overlays = overlays;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.AGENT;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) throws IOException {
        var file = file(component, projectDir);
        var content = content(component);
        var path = "agent " + component.name() + " in the project's app folder";
        return switch (ProjectFiles.compare(file, content)) {
            case MISSING -> CodeExecutingPaths.agentDeclaresHooks(content)
                    ? PlannedChange.of(component, ChangeAction.NEEDS_CONFIRMATION, CodeExecutingPaths.runsCode(path + " (declares hooks)")).withPreview(content)
                    : PlannedChange.of(component, ChangeAction.CREATE, "Create " + path).withPreview(content);
            case IDENTICAL -> PlannedChange.of(component, ChangeAction.PRESENT, "Agent already defined");
            case DIFFERENT -> PlannedChange.of(component, ChangeAction.CONFLICT, "An agent file with this name exists and differs, it is left unchanged");
        };
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) throws IOException {
        var file = file(component, projectDir);
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        overlays.overlayOf(projectDir).write(file, content(component));
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Agent created");
    }

    private Path file(PresetComponent component, Path projectDir) {
        return ProjectFiles.resolve(overlays.overlayOf(projectDir).agentsDirectory(), component.name() + ".md");
    }

    private static String content(PresetComponent component) {
        return component.payload().path("content").asString("");
    }
}
