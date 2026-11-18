package com.github.krpkbhdn.astrolabe.codemode.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpServerErrorExceptionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void jsonRpcErrorAnswerIsAServerError() throws Exception {
        var response = MAPPER.readTree("{\"error\":{\"code\":-32602,\"message\":\"bad params\"}}");

        assertThatThrownBy(() -> JsonRpcMcpClient.resultOf(response)).isInstanceOf(McpServerErrorException.class)
                .isInstanceOf(McpClientException.class).hasMessageContaining("-32602").hasMessageContaining("bad params");
    }

    @Test
    void toolResultFlaggedAsErrorIsNotAServerErrorException() throws Exception {
        var response = MAPPER.readTree("{\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}}");

        assertThat(JsonRpcMcpClient.resultOf(response).path("isError").asBoolean()).isTrue();
    }

    @Test
    void authAndTransportFailuresAreNotServerErrors() {
        assertThat(new McpAuthRequiredException(401)).isNotInstanceOf(McpServerErrorException.class);
        assertThat(new McpClientException("timeout")).isNotInstanceOf(McpServerErrorException.class);
    }

    @Test
    void hugeServerTextIsCut() {
        var message = new McpServerErrorException(1, "x".repeat(1_000_000)).getMessage();

        assertThat(message).hasSizeLessThan(McpServerErrorException.MAX_MESSAGE_CHARS + 100).endsWith("...");
    }
}
