package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.SettingsFragmentMerger;
import com.github.krpkbhdn.ccmanager.git.GitLocalExclude;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Merges a fragment into .claude/settings.json; keys that already hold a different value stay as they are and are reported. */
@Component
class SettingsApplier implements PresetComponentApplier {

    private static final String SHARED_SETTINGS = ".claude/settings.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GitLocalExclude localExclude;

    SettingsApplier(GitLocalExclude localExclude) {
        this.localExclude = localExclude;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.SETTINGS;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) throws IOException {
        var settings = targetOf(component);
        var file = ProjectFiles.resolve(projectDir, settings);
        var fragment = component.payload().path("fragment");
        var result = SettingsFragmentMerger.merge(existing(file), fragment);
        var skipped = result.conflicts().isEmpty() ? "" : ", keys left as they are: " + String.join(", ", result.conflicts());
        if (!result.changed()) {
            return result.conflicts().isEmpty() ? PlannedChange.of(component, ChangeAction.PRESENT, "Settings already contain these values")
                    : PlannedChange.of(component, ChangeAction.CONFLICT, "Every key differs from the current value: " + String.join(", ", result.conflicts()));
        }
        var preview = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fragment);
        var executable = CodeExecutingPaths.executableSettingsKeys(fragment);
        if (!executable.isEmpty()) {
            return PlannedChange.of(component, ChangeAction.NEEDS_CONFIRMATION, "Warning: runs code or changes permissions "
                    + CodeExecutingPaths.settingsAudience(ProjectFiles.isLocal(component)) + " (" + String.join(", ", executable) + "), add it to " + settings + " yourself after reviewing it").withPreview(preview);
        }
        var action = Files.exists(file) ? ChangeAction.UPDATE : ChangeAction.CREATE;
        return PlannedChange.of(component, action, "Merge into " + settings + skipped).withPreview(preview);
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) throws IOException {
        var settings = targetOf(component);
        var file = ProjectFiles.resolve(projectDir, settings);
        if (ProjectFiles.isLocal(component)) {
            localExclude.ensureExcluded(projectDir, settings);
        }
        var result = SettingsFragmentMerger.mergeFile(file, component.payload().path("fragment"));
        var note = result.conflicts().isEmpty() ? "" : ", left as they are: " + String.join(", ", result.conflicts());
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Settings merged" + note);
    }

    private static String targetOf(PresetComponent component) {
        return ProjectFiles.isLocal(component) ? LocalConfigFiles.SETTINGS : SHARED_SETTINGS;
    }

    private static JsonNode existing(Path file) throws IOException {
        if (!Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            var parsed = MAPPER.readTree(ProjectFiles.read(file));
            if (parsed.isObject()) {
                return parsed;
            }
            if (parsed.isMissingNode() || parsed.isNull()) {
                return MAPPER.createObjectNode();
            }
        } catch (RuntimeException e) {
            // reported below without the parser's text, which may quote settings
        }
        throw new ExtensionException("settings.json is not a valid JSON object, nothing was changed");
    }
}
