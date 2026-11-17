package com.github.krpkbhdn.ccmanager.preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PresetComponentValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static PresetComponentSpec codeMode(String payload) {
        return PresetComponentValidator.validate(PresetComponentKind.CODE_MODE, "code-mode", JSON.readTree(payload));
    }

    @Test
    void codeModeKeepsTrimmedServersInOrderAndDropsUnknownFields() {
        var spec = codeMode("{\"servers\":[\" github \",\"playwright\"],\"extra\":1}");

        assertThat(spec.payload().toString()).isEqualTo("{\"servers\":[\"github\",\"playwright\"]}");
    }

    @Test
    void codeModeRejectsMissingEmptyDuplicateBlankAndNonTextServers() {
        assertThatThrownBy(() -> codeMode("{}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("list of MCP server names");
        assertThatThrownBy(() -> codeMode("{\"servers\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codeMode("{\"servers\":[\"a\",\" a\"]}")).hasMessageContaining("unique");
        assertThatThrownBy(() -> codeMode("{\"servers\":[\" \"]}")).hasMessageContaining("empty");
        assertThatThrownBy(() -> codeMode("{\"servers\":[1]}")).hasMessageContaining("text");
    }

    @Test
    void codeModeRejectsMoreThanTheMaximumServers() {
        var names = new StringBuilder("{\"servers\":[");
        for (var i = 0; i < 51; i++) {
            names.append(i == 0 ? "" : ",").append("\"s").append(i).append('"');
        }

        assertThatThrownBy(() -> codeMode(names.append("]}").toString())).hasMessageContaining("At most 50");
    }

    private static PresetComponentSpec validate(PresetComponentKind kind, String name, String payload) {
        return PresetComponentValidator.validate(kind, name, JSON.readTree(payload));
    }

    @Test
    void localScopeIsKeptAndSharedOrAbsentScopeIsDropped() {
        assertThat(validate(PresetComponentKind.INSTRUCTION, "Style", "{\"markdown\":\"x\",\"scope\":\"local\"}").payload().path("scope").asString()).isEqualTo("local");
        assertThat(validate(PresetComponentKind.INSTRUCTION, "Style", "{\"markdown\":\"x\",\"scope\":\"shared\"}").payload().has("scope")).isFalse();
        assertThat(validate(PresetComponentKind.SETTINGS, "s", "{\"fragment\":{},\"scope\":\"local\"}").payload().path("scope").asString()).isEqualTo("local");
    }

    @Test
    void pluginPayloadIsEmptyWithoutScopeAndLocalWhenGiven() {
        assertThat(validate(PresetComponentKind.PLUGIN, "a@b", "{}").payload().toString()).isEqualTo("{}");
        assertThat(validate(PresetComponentKind.PLUGIN, "a@b", "{\"scope\":\"local\"}").payload().toString()).isEqualTo("{\"scope\":\"local\"}");
    }

    @Test
    void scopeOnAKindWithoutScopeIsRejected() {
        assertThatThrownBy(() -> validate(PresetComponentKind.FILE, "f", "{\"path\":\"a.txt\",\"content\":\"x\",\"scope\":\"local\"}")).hasMessageContaining("no scope");
        assertThatThrownBy(() -> validate(PresetComponentKind.MARKETPLACE, "m", "{\"source\":\"o/r\",\"scope\":\"shared\"}")).hasMessageContaining("no scope");
        assertThatThrownBy(() -> validate(PresetComponentKind.MCP_SERVER, "s", "{\"config\":{\"command\":\"x\"},\"scope\":\"local\"}")).hasMessageContaining("no scope");
    }

    @Test
    void unknownScopeIsRejected() {
        assertThatThrownBy(() -> validate(PresetComponentKind.PLUGIN, "a@b", "{\"scope\":\"team\"}")).hasMessageContaining("scope");
        assertThatThrownBy(() -> validate(PresetComponentKind.INSTRUCTION, "Style", "{\"markdown\":\"x\",\"scope\":1}")).hasMessageContaining("scope");
    }
}
