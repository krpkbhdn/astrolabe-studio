package com.github.krpkbhdn.ccmanager.codemode.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonRpcMcpClientTest {

    /** A transport that answers tools/list from a function of the cursor and counts the requests. */
    private static final class PagedClient extends JsonRpcMcpClient {
        final AtomicInteger requests = new AtomicInteger();
        private final java.util.function.IntFunction<JsonNode> page;

        PagedClient(java.util.function.IntFunction<JsonNode> page) {
            super("paged");
            this.page = page;
        }

        @Override
        JsonNode request(String method, JsonNode params) {
            return page.apply(requests.getAndIncrement());
        }

        @Override
        void notifyServer(String method) {
        }

        @Override
        public void close() {
        }
    }

    private static JsonNode page(int tools, String nextCursor) {
        var result = MAPPER().createObjectNode();
        var array = result.putArray("tools");
        for (int i = 0; i < tools; i++) {
            array.addObject().put("name", "t" + i);
        }
        if (nextCursor != null) {
            result.put("nextCursor", nextCursor);
        }
        return result;
    }

    private static tools.jackson.databind.json.JsonMapper MAPPER() {
        return JsonRpcMcpClient.MAPPER;
    }

    @Test
    void endlessPagesFailWithAFixedMessageAfterTheCap() {
        var client = new PagedClient(index -> page(1, "cursor-" + index));

        assertThatThrownBy(client::listTools).isInstanceOf(McpClientException.class).hasMessage(JsonRpcMcpClient.TOO_MANY_TOOLS);
        assertThat(client.requests.get()).isEqualTo(JsonRpcMcpClient.MAX_TOOL_PAGES);
    }

    @Test
    void tooManyToolsFailWithAFixedMessage() {
        var client = new PagedClient(index -> page(JsonRpcMcpClient.MAX_TOOLS / 2 + 1, index == 0 ? "next" : null));

        assertThatThrownBy(client::listTools).isInstanceOf(McpClientException.class).hasMessage(JsonRpcMcpClient.TOO_MANY_TOOLS);
    }

    @Test
    void exactlyTheLimitsAreAccepted() {
        var pages = new PagedClient(index -> page(1, index < JsonRpcMcpClient.MAX_TOOL_PAGES - 1 ? "cursor-" + index : null));
        var tools = new PagedClient(index -> page(JsonRpcMcpClient.MAX_TOOLS, null));

        assertThat(pages.listTools()).hasSize(JsonRpcMcpClient.MAX_TOOL_PAGES);
        assertThat(tools.listTools()).hasSize(JsonRpcMcpClient.MAX_TOOLS);
    }
}
