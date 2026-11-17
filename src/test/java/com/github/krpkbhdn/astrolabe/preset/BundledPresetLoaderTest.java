package com.github.krpkbhdn.astrolabe.preset;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundledPresetLoaderTest {

    @Test
    void bundledPresetsAreGeneralFrontendAndAngularAllValid() {
        var presets = new BundledPresetLoader().presets();

        assertThat(presets).extracting(BundledPreset::key).containsExactly("general", "frontend", "angular");
        presets.forEach(preset -> {
            assertThat(preset.components()).extracting(c -> c.kind() + ":" + c.name()).doesNotHaveDuplicates();
            preset.components().forEach(c -> PresetComponentValidator.validate(c.kind(), c.name(), c.payload()));
        });
        var frontend = presets.get(1);
        var angular = presets.get(2);
        assertThat(frontend.match()).containsExactly("frontend");
        assertThat(angular.match()).containsExactly("angular", "frontend");
        assertThat(frontend.components()).extracting(c -> c.kind() + ":" + c.name())
                .containsAll(presets.getFirst().components().stream().map(c -> c.kind() + ":" + c.name()).toList())
                .contains("PLUGIN:playwright@claude-plugins-official", "INSTRUCTION:Frontend tools");
        assertThat(angular.components()).extracting(c -> c.kind() + ":" + c.name())
                .containsAll(frontend.components().stream().map(c -> c.kind() + ":" + c.name()).toList())
                .contains("MCP_SERVER:angular-cli", "SKILL:angular-developer", "INSTRUCTION:Angular tools");
        assertThat(presets).extracting(BundledPreset::version).containsExactly(1, 2, 2);
        assertThat(instructionScopes(frontend)).containsEntry("Frontend tools", "local").containsEntry("Duplicate code", "");
        assertThat(instructionScopes(angular)).containsEntry("Angular tools", "local").containsEntry("Frontend tools", "local").containsEntry("Conventions", "");
        assertThat(instructionScopes(presets.getFirst()).values()).containsOnly("");

        assertThat(presets).first().satisfies(general -> {
            assertThat(general.key()).isEqualTo("general");
            assertThat(general.version()).isEqualTo(1);
            assertThat(general.name()).isEqualTo("General");
            assertThat(general.components()).extracting(c -> c.kind() + ":" + c.name()).containsExactly(
                    "INSTRUCTION:Duplicate code", "INSTRUCTION:Clean code", "INSTRUCTION:Comments", "INSTRUCTION:Conventions",
                    "SKILL:jscpd", "SKILL:dry-refactoring", "FILE:.jscpd.json");
            assertThat(general.components().getFirst().payload().path("markdown").asString()).startsWith("- Before writing anything new").doesNotContain("Claude setup");
        });
    }

    private static java.util.Map<String, String> instructionScopes(BundledPreset preset) {
        return preset.components().stream().filter(c -> c.kind() == PresetComponentKind.INSTRUCTION)
                .collect(java.util.stream.Collectors.toMap(PresetComponentSpec::name, c -> c.payload().path("scope").asString("")));
    }

    private static BundledPreset layer(String key) {
        return new BundledPresetLoader("presets/layers-test.json").presets().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> origins(BundledPreset preset) {
        return preset.components().stream().map(c -> c.name() + "=" + c.payload().path("from").asString()).toList();
    }

    @Test
    void includedComponentsComeFirstAndOwnOnesOverrideBySameKindAndName() {
        assertThat(origins(layer("web"))).containsExactly("a=base", "b=base", "c=web", "d=web");
    }

    @Test
    void includesAreFlattenedRecursively() {
        assertThat(origins(layer("app"))).containsExactly("a=base", "b=base", "c=web", "d=web");
    }

    @Test
    void matchTraitsArePassedThroughAndDefaultToEmpty() {
        assertThat(layer("web").match()).containsExactly("frontend", "node");
        assertThat(layer("base").match()).isEmpty();
    }

    @Test
    void includeCycleFailsWithThePath() {
        assertThatThrownBy(() -> new BundledPresetLoader("presets/layers-cycle-test.json")).hasMessageContaining("cycle").hasMessageContaining("a -> b -> a");
    }

    @Test
    void duplicateKeyFails() {
        assertThatThrownBy(() -> new BundledPresetLoader("presets/layers-duplicate-test.json")).hasMessageContaining("used twice: a");
    }

    @Test
    void unknownIncludeFailsNamingTheKey() {
        assertThatThrownBy(() -> new BundledPresetLoader("presets/layers-unknown-test.json")).hasMessageContaining("unknown preset 'missing'");
    }

    @Test
    void blankMatchTraitFails() {
        assertThatThrownBy(() -> new BundledPresetLoader("presets/layers-blank-match-test.json")).hasMessageContaining("blank match");
    }

    @Test
    void componentsPassValidationAndSkillsCarryTheirFiles() {
        var general = new BundledPresetLoader().presets().getFirst();

        general.components().forEach(c -> PresetComponentValidator.validate(c.kind(), c.name(), c.payload()));
        var jscpd = general.components().stream().filter(c -> c.name().equals("jscpd")).findFirst().orElseThrow();
        assertThat(jscpd.payload().path("files").has("SKILL.md")).isTrue();
        var file = general.components().getLast().payload();
        assertThat(file.path("path").asString()).isEqualTo(".jscpd.json");
        assertThat(file.path("content").asString()).contains("minLines").doesNotContain("\"format\"");
        assertThat(file.path("gitignore").get(0).asString()).isEqualTo("jscpd-report/");
    }
}
