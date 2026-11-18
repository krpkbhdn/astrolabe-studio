package com.github.krpkbhdn.astrolabe.claude.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.claude.session.TranscriptEntry.Kind;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TranscriptLineParserTest {

    private final TranscriptLineParser parser = new TranscriptLineParser(JsonMapper.builder().build());

    @Test
    void readsMetadataAndLinksToolCallToItsResult() {
        var call = parser.parse("{\"type\":\"assistant\",\"uuid\":\"u1\",\"sessionId\":\"s1\",\"cwd\":\"/p\","
                + "\"timestamp\":\"2026-01-02T03:04:05Z\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\","
                + "\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}").orElseThrow();
        var result = parser.parse("{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"a.txt\"}]}}").orElseThrow();

        assertThat(call.uuid()).isEqualTo("u1");
        assertThat(call.sessionId()).isEqualTo("s1");
        assertThat(call.cwd()).isEqualTo("/p");
        assertThat(call.at()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(call.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(Kind.TOOL_CALL);
            assertThat(entry.toolUseId()).isEqualTo("t1");
            assertThat(entry.uuid()).isEqualTo("u1");
        });
        assertThat(result.entries().getFirst().toolUseId()).isEqualTo("t1");
        assertThat(result.at()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void toolCallKeepsTheWholeInputAlthoughItsShownTextIsCut() {
        var command = "echo " + "ab cd ".repeat(100) + " && cat .env";
        var call = parser.parse("{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"" + command + "\"}}]}}").orElseThrow();

        var entry = call.entries().getFirst();

        assertThat(entry.text()).doesNotContain(".env");
        assertThat(entry.fullInput()).contains(".env");
    }

    @Test
    void secretStraddlingTheTextLimitIsMaskedBeforeTheCut() {
        var secret = "sk-abcdefghijklmnopqrstuvwxyz123456";
        var text = "a".repeat(5990) + " " + secret + " tail";
        var line = parser.parse("{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"" + text + "\"}}").orElseThrow();

        assertThat(line.entries().getFirst().text()).doesNotContain("sk-abcd");
    }

    @Test
    void unknownAndBrokenLinesYieldNothing() {
        assertThat(parser.parse("not json")).isEmpty();
        assertThat(parser.parse("{\"type\":\"attachment\"}")).isEmpty();
    }
}
