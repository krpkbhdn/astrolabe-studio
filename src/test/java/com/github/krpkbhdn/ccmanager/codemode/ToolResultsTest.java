package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolResultsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void errorFlaggedResultBecomesAnExceptionWithTheServerText() throws Exception {
        var result = MAPPER.readTree("{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"disk full\"}]}");

        assertThatThrownBy(() -> ToolResults.toScriptValue(result, null)).isInstanceOf(IllegalStateException.class).hasMessage("disk full");
    }

    @Test
    void hugeErrorTextIsCut() throws Exception {
        var result = MAPPER.readTree("{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(100_000) + "\"}]}");

        assertThatThrownBy(() -> ToolResults.toScriptValue(result, null)).hasMessageEndingWith("...")
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(ToolResults.MAX_ERROR_CHARS + 3));
    }
}
