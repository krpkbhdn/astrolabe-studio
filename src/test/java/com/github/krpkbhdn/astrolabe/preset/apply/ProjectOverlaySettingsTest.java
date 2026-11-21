package com.github.krpkbhdn.astrolabe.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProjectOverlaySettingsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private ProjectOverlay overlay;
    private ProjectOverlaySettings settings;

    @BeforeEach
    void setUp() {
        overlay = new ProjectOverlay(dir.resolve("overlay"));
        settings = new ProjectOverlaySettings(null);
    }

    private void seed(String json) throws IOException {
        Files.createDirectories(overlay.root());
        Files.writeString(overlay.settingsFile(), json);
    }

    @Test
    void updateCreatesTheFileWhenSettingsAreMissing() {
        settings.update(overlay, node -> {
            node.putObject("permissions").putArray("deny").add("Bash(rm:*)");
            return node;
        });

        assertThat(MAPPER.readTree(overlay.settingsFile()).at("/permissions/deny/0").asText()).isEqualTo("Bash(rm:*)");
    }

    @Test
    void updateMergesTheFragmentAndKeepsUnrelatedKeys() throws IOException {
        seed("{\"model\":\"opus\",\"enabledPlugins\":{\"a\":true}}");

        settings.update(overlay, node -> {
            node.putObject("enabledPlugins").put("b", false);
            return node;
        });

        var result = MAPPER.readTree(overlay.settingsFile());
        assertThat(result.path("model").asText()).isEqualTo("opus");
        assertThat(result.at("/enabledPlugins/b").asBoolean(true)).isFalse();
    }

    @Test
    void updateLeavesTheFileAloneWhenTheChangeReturnsNull() throws IOException {
        seed("{\"model\":\"opus\"}");

        settings.update(overlay, node -> null);

        assertThat(overlay.settingsFile()).hasContent("{\"model\":\"opus\"}");
    }

    @Test
    void updateKeepsTheOldFileWhenTheChangeThrows() throws IOException {
        seed("{\"model\":\"opus\"}");

        assertThatThrownBy(() -> settings.update(overlay, node -> {
            node.put("model", "haiku");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(overlay.settingsFile()).hasContent("{\"model\":\"opus\"}");
    }

    @Test
    void updateRefusesAFileThatIsNotAJsonObjectAndKeepsIt() throws IOException {
        seed("[1,2]");

        assertThatThrownBy(() -> settings.update(overlay, node -> node)).isInstanceOf(ExtensionException.class);

        assertThat(overlay.settingsFile()).hasContent("[1,2]");
    }

    @Test
    void updateLeavesNoTemporaryFilesBesideTheSettings() throws IOException {
        settings.update(overlay, node -> node.put("k", "v"));

        try (var entries = Files.list(overlay.root())) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("settings.json");
        }
    }

    @Test
    void readOfAMissingFileIsAnEmptyObject() {
        assertThat(ProjectOverlaySettings.read(overlay)).isEmpty();
    }
}
