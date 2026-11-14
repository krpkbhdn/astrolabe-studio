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
}
