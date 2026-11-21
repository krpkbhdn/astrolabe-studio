package com.github.krpkbhdn.astrolabe.codemode.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpMcpClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int status;
    private volatile boolean oversize;
    private volatile boolean oversizeAsJson;
    private volatile String errorBody;
    private final List<String> deletedSessions = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
    private McpClient client;

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        errorBody = null;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deletedSessions.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id") + "/" + exchange.getRequestHeaders().getFirst("X-Token"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        var body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        seenHeaders.add(Map.of("session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id") == null ? "" : exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                "token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Token")), "method", body.path("method").asString()));
        if (status != 200 && errorBody != null) {
            var payload = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
        } else if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
        } else if (!body.has("id")) {
            exchange.sendResponseHeaders(202, -1);
        } else {
            respond(exchange, body);
        }
        exchange.close();
    }

    private void respond(HttpExchange exchange, tools.jackson.databind.JsonNode request) throws IOException {
        var reply = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", request.get("id"));
        var result = reply.putObject("result");
        switch (request.get("method").asString()) {
            case "initialize" -> {
                result.put("protocolVersion", request.path("params").path("protocolVersion").asString());
                exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
            }
            case "tools/list" -> result.putArray("tools").addObject().put("name", "t").putObject("inputSchema");
            default -> result.putArray("content").addObject().put("type", "text").put("text", oversize ? "x".repeat(JsonRpcMcpClient.MAX_RESPONSE_CHARS) : "ok");
        }
        var json = MAPPER.writeValueAsString(reply);
        var sse = "tools/call".equals(request.get("method").asString()) && !oversizeAsJson;
        var payload = (sse ? "event: message\ndata: " + json + "\n\n" : json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private McpClient connect() {
        return connect("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private McpClient connect(String url) {
        var definition = new McpServerDefinition("remote", ExtensionScope.USER, McpServerDefinition.Transport.HTTP, "", List.of(), Map.of(),
                url, Map.of("X-Token", "abc"), null);
        var properties = TestProperties.of(Duration.ofSeconds(5), 1, 1, 1, Duration.ofMinutes(1), "image", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        client = new McpClients(properties, new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, Path.of("."))).create(definition);
        return client;
    }

    @Test
    void handshakeListAndSseCallKeepSessionAndSendHeaders() {
        var connected = connect();
        connected.initialize();

        assertThat(connected.listTools()).extracting("name").containsExactly("t");
        assertThat(connected.callTool("t", null).path("content").get(0).path("text").asString()).isEqualTo("ok");
        assertThat(seenHeaders).extracting(h -> h.get("method")).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
        assertThat(seenHeaders.subList(1, 4)).allSatisfy(h -> assertThat(h).containsEntry("session", "s-1").containsEntry("token", "abc"));
    }

    @Test
    void unauthorizedBecomesAuthRequired() {
        status = 401;
        var connected = connect();

        assertThatThrownBy(connected::initialize).isInstanceOf(McpAuthRequiredException.class);
    }

    @Test
    void invalidUrlFailsWithoutQuotingTheUrl() {
        var connected = connect("http://exa mple/mcp?token=hunter2");

        assertThatThrownBy(connected::initialize).isInstanceOf(McpClientException.class).hasMessageNotContaining("hunter2").hasMessageNotContaining("exa mple")
                .hasMessageContaining("invalid");
    }

    @Test
    void unreachableServerFailsWithoutQuotingTheUrl() {
        var connected = connect("http://127.0.0.1:1/mcp?token=hunter2");

        assertThatThrownBy(connected::initialize).isInstanceOf(McpClientException.class).hasMessageNotContaining("hunter2").hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void oversizedSseResponseIsRefused() {
        var connected = connect();
        connected.initialize();
        oversize = true;

        assertThatThrownBy(() -> connected.callTool("t", null)).isInstanceOf(McpClientException.class).hasMessage(JsonRpcMcpClient.RESPONSE_TOO_LARGE);
    }

    @Test
    void oversizedJsonResponseIsRefused() {
        var connected = connect();
        connected.initialize();
        oversize = true;
        oversizeAsJson = true;

        assertThatThrownBy(() -> connected.callTool("t", null)).isInstanceOf(McpClientException.class).hasMessage(JsonRpcMcpClient.RESPONSE_TOO_LARGE);
    }

    @Test
    void otherHttpErrorBecomesClientException() {
        status = 500;
        var connected = connect();

        assertThatThrownBy(connected::initialize).isInstanceOf(McpClientException.class).hasMessageContaining("500");
    }

    @Test
    void clientErrorWithAJsonRpcBodyIsAServerErrorAndKeepsTheSession() {
        var connected = connect();
        connected.initialize();
        status = 422;
        errorBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32602,\"message\":\"bad arguments\"}}";

        assertThatThrownBy(() -> connected.callTool("t", null)).isInstanceOf(McpServerErrorException.class).hasMessageContaining("-32602").hasMessageContaining("bad arguments");
        status = 200;
        assertThat(connected.callTool("t", null).path("content")).isNotEmpty();
        assertThat(seenHeaders.getLast()).containsEntry("session", "s-1");
    }

    @Test
    void clientErrorWithoutAJsonRpcBodyStaysATransportFailure() {
        var connected = connect();
        status = 400;
        errorBody = "<html>bad request</html>";

        assertThatThrownBy(connected::initialize).isInstanceOf(McpClientException.class).isNotInstanceOf(McpServerErrorException.class).hasMessageContaining("400");
    }

    @Test
    void serverErrorWithAJsonRpcBodyIsAServerErrorButBareServerErrorIsNot() {
        var connected = connect();
        status = 500;
        errorBody = "{\"error\":{\"code\":-32603,\"message\":\"internal\"}}";
        assertThatThrownBy(connected::initialize).isInstanceOf(McpServerErrorException.class).hasMessageContaining("internal");
        errorBody = null;

        assertThatThrownBy(connected::initialize).isNotInstanceOf(McpServerErrorException.class).hasMessageContaining("500");
    }

    @Test
    void usingAClosedClientFailsWithAFixedMessage() {
        var connected = connect();
        connected.close();

        assertThatThrownBy(connected::initialize).isInstanceOf(McpClientException.class).hasMessage("The connection to the server remote is closed");
        assertThatThrownBy(() -> connected.callTool("t", null)).isInstanceOf(McpClientException.class).hasMessage("The connection to the server remote is closed");
    }

    @Test
    void closeEndsTheSessionOnceWithTheConfiguredHeaders() {
        var connected = connect();
        connected.initialize();

        connected.close();
        connected.close();

        assertThat(deletedSessions).containsExactly("s-1/abc");
    }

    @Test
    void closeSendsNothingWithoutASessionAndNeverThrowsWhenTheServerIsGone() {
        connect().close();
        assertThat(deletedSessions).isEmpty();

        var connected = connect();
        connected.initialize();
        server.stop(0);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), connected::close);
    }
}
