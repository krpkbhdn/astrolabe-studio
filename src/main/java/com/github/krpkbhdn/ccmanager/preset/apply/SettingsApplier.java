package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.extension.SettingsFragmentMerger;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Merges a fragment into the app's own settings of the project (its overlay, never the repository); keys that already hold a different value stay as they are and are reported. */
@Component
class SettingsApplier implements PresetComponentApplier {

    private static final String TARGET = "the project settings of the app";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ProjectOverlaySettings overlays;

    SettingsApplier(ProjectOverlaySettings overlays) {
        this.overlays = overlays;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.SETTINGS;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) throws IOException {
        var existing = overlays.read(projectDir);
        var fragment = component.payload().path("fragment");
        var result = SettingsFragmentMerger.merge(existing, fragment);
        var skipped = result.conflicts().isEmpty() ? "" : ", keys left as they are: " + String.join(", ", result.conflicts());
        if (!result.changed()) {
            return result.conflicts().isEmpty() ? PlannedChange.of(component, ChangeAction.PRESENT, "Settings already contain these values")
                    : PlannedChange.of(component, ChangeAction.CONFLICT, "Every key differs from the current value: " + String.join(", ", result.conflicts()));
        }
        var preview = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fragment);
        var executable = CodeExecutingPaths.executableSettingsKeys(fragment);
        if (!executable.isEmpty()) {
            return PlannedChange.of(component, ChangeAction.NEEDS_CONFIRMATION, "Warning: runs code or changes permissions (" + String.join(", ", executable) + "), not applied automatically").withPreview(preview);
        }
        var action = existing.isEmpty() ? ChangeAction.CREATE : ChangeAction.UPDATE;
        return PlannedChange.of(component, action, "Merge into " + TARGET + skipped).withPreview(preview);
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) throws IOException {
        var merged = new AtomicReference<SettingsFragmentMerger.Result>();
        overlays.update(overlays.overlayOf(projectDir), existing -> {
            merged.set(SettingsFragmentMerger.merge(existing, component.payload().path("fragment")));
            return merged.get().changed() ? (ObjectNode) merged.get().merged() : null;
        });
        var result = merged.get();
        var note = result.conflicts().isEmpty() ? "" : ", left as they are: " + String.join(", ", result.conflicts());
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Settings merged" + note);
    }
}
