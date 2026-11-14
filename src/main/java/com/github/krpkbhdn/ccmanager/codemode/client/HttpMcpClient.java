package com.github.krpkbhdn.ccmanager.codemode.client;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/** MCP over streamable HTTP: every message is a POST whose answer is JSON or an SSE stream; the legacy SSE transport is not supported. */
final class HttpMcpClient extends JsonRpcMcpClient {

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String DATA_PREFIX = "data:";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SESSION_END_TIMEOUT = Duration.ofSeconds(2);
    /** A JSON-RPC error body is a few lines; anything larger is not read. */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private final McpServerDefinition definition;
    private final CodeModeProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ExecutorService exchanges = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;

    HttpMcpClient(McpServerDefinition definition, CodeModeProperties properties) {
        super(definition.name());
        this.definition = definition;
        this.properties = properties;
    }

    @Override
    JsonNode request(String method, JsonNode params) {
        var id = ids.incrementAndGet();
        var exchange = submit(() -> resultOf(exchange(requestMessage(id, method, params), id)));
        try {
            return exchange.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new McpClientException("The server " + definition.name() + " did not answer " + method + " in time");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted while waiting for " + method);
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        }
    }

    @Override
    void notifyServer(String method) {
        var sent = submit(() -> {
            post(notificationMessage(method)).body().close();
            return null;
        });
        try {
            sent.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sent.cancel(true);
            throw new McpClientException("The server " + definition.name() + " did not accept " + method + " in time");
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted while sending " + method);
        }
    }

    private <T> Future<T> submit(Callable<T> task) {
        try {
            return exchanges.submit(task);
        } catch (RejectedExecutionException e) {
            throw new McpClientException("The connection to the server " + definition.name() + " is closed");
        }
    }

    private JsonNode exchange(JsonNode message, long id) throws IOException {
        var response = post(message);
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        try (var body = response.body()) {
            if (!contentType.contains("text/event-stream")) {
                var bytes = body.readNBytes(MAX_RESPONSE_CHARS + 1);
                if (bytes.length > MAX_RESPONSE_CHARS) {
                    throw new McpClientException(RESPONSE_TOO_LARGE);
                }
                return MAPPER.readTree(bytes);
            }
            return awaitInStream(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), id);
        }
    }

    private JsonNode awaitInStream(BufferedReader lines, long id) throws IOException {
        var data = new StringBuilder();
        String line;
        while ((line = readBoundedLine(lines)) != null) {
            if (line.startsWith(DATA_PREFIX)) {
                data.append(line.substring(DATA_PREFIX.length()).stripLeading());
                if (data.length() > MAX_RESPONSE_CHARS) {
                    throw new McpClientException(RESPONSE_TOO_LARGE);
                }
            } else if (line.isEmpty() && !data.isEmpty()) {
                var event = MAPPER.readTree(data.toString());
                data.setLength(0);
                if (event.has("method") && event.has("id")) {
                    post(replyToServerRequest(event)).body().close();
                } else if (event.path("id").asLong(-1) == id) {
                    return event;
                }
            }
        }
        throw new McpClientException("The server " + definition.name() + " ended the stream without an answer");
    }

    /** Parse errors of the URL or headers quote their input, which may hold a token, so they are replaced by a fixed message. */
    private HttpRequest buildRequest(JsonNode message) throws IOException {
        try {
            var request = HttpRequest.newBuilder(URI.create(definition.url())).timeout(properties.timeout())
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(message)));
            return withSessionHeaders(request).build();
        } catch (IllegalArgumentException e) {
            throw new McpClientException("The URL or headers of the server " + definition.name() + " are invalid");
        }
    }

    private HttpRequest.Builder withSessionHeaders(HttpRequest.Builder request) {
        definition.headers().forEach(request::header);
        if (sessionId != null) {
            request.header(SESSION_HEADER, sessionId);
        }
        if (protocolVersion() != null) {
            request.header(VERSION_HEADER, protocolVersion());
        }
        return request;
    }

    private HttpResponse<InputStream> post(JsonNode message) throws IOException {
        var request = buildRequest(message);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        var status = response.statusCode();
        if (status >= 400) {
            throw failedStatus(status, response);
        }
        response.headers().firstValue(SESSION_HEADER).ifPresent(id -> sessionId = id);
        return response;
    }

    /** A healthy server may explain a refusal (400, 422) with a JSON-RPC error body; that is a server error, the connection and its session stay usable. */
    private McpClientException failedStatus(int status, HttpResponse<InputStream> response) {
        if (status == 401 || status == 403) {
            closeQuietly(response.body());
            return new McpAuthRequiredException(status);
        }
        try (var body = response.body()) {
            var error = MAPPER.readTree(body.readNBytes(MAX_ERROR_BODY_BYTES)).path("error");
            if (error.isObject()) {
                return new McpServerErrorException(error.path("code").asInt(), error.path("message").asString(""));
            }
        } catch (IOException | RuntimeException e) {
            // not a JSON-RPC body: a plain transport failure
        }
        return new McpClientException("The server " + definition.name() + " answered HTTP " + status);
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            // nothing left to release
        }
    }

    @Override
    public void close() {
        endSession();
        exchanges.shutdownNow();
        http.close();
    }

    /** Best effort and bounded: tells the server to drop its session; any failure is ignored. */
    private void endSession() {
        var id = sessionId;
        sessionId = null;
        if (id == null) {
            return;
        }
        try {
            var request = withSessionHeaders(HttpRequest.newBuilder(URI.create(definition.url())).timeout(SESSION_END_TIMEOUT).DELETE())
                    .header(SESSION_HEADER, id).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // the server may be gone or may not support ending sessions
        }
    }
}
