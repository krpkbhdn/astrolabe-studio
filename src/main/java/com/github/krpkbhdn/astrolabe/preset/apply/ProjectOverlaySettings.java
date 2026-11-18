package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.ProjectOverlays;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads and edits the overlay {@code settings.json} of the project that owns a folder: fragments and plugin enablement live there, not in the repository. */
@Component
public class ProjectOverlaySettings {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ENABLED_PLUGINS = "enabledPlugins";

    private final ProjectOverlays overlays;

    public ProjectOverlaySettings(ProjectOverlays overlays) {
        this.overlays = overlays;
    }

    /** @throws ExtensionException when no registered project has this folder */
    public ProjectOverlay overlayOf(Path projectDir) {
        return overlays.require(projectDir);
    }

    /** The overlay settings as an object, empty when the file is missing. @throws ExtensionException when it is not a JSON object */
    public ObjectNode read(Path projectDir) {
        return read(overlayOf(projectDir));
    }

    public static ObjectNode read(ProjectOverlay overlay) {
        try {
            return overlay.readObject(overlay.settingsFile());
        } catch (IllegalArgumentException e) {
            throw new ExtensionException("The project settings of the app are not a JSON object, nothing was changed");
        } catch (IOException e) {
            throw new ExtensionException("Could not read the project settings of the app: " + e.getClass().getSimpleName());
        }
    }

    public synchronized void write(ProjectOverlay overlay, JsonNode settings) {
        try {
            overlay.write(overlay.settingsFile(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings) + System.lineSeparator());
        } catch (IOException e) {
            throw new ExtensionException("Could not write the project settings of the app: " + e.getClass().getSimpleName());
        }
    }

    /** Reads, changes and writes the settings under one lock; [change] returns the settings to write, or null to leave the file alone. */
    public synchronized void update(ProjectOverlay overlay, UnaryOperator<ObjectNode> change) {
        var changed = change.apply(read(overlay));
        if (changed != null) {
            write(overlay, changed);
        }
    }

    /** What the overlay says about a plugin: true or false when set, empty when it says nothing or no readable settings exist. */
    public Optional<Boolean> pluginSetting(Path projectDir, String pluginId) {
        try {
            var value = read(projectDir).path(ENABLED_PLUGINS).path(pluginId);
            return value.isBoolean() ? Optional.of(value.asBoolean()) : Optional.empty();
        } catch (ExtensionException e) {
            return Optional.empty();
        }
    }

    public void setPlugin(Path projectDir, String pluginId, boolean enabled) {
        update(overlayOf(projectDir), settings -> {
            var plugins = settings.path(ENABLED_PLUGINS) instanceof ObjectNode existing ? existing : settings.putObject(ENABLED_PLUGINS);
            plugins.put(pluginId, enabled);
            return settings;
        });
    }
}
