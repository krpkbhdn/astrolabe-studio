package com.github.krpkbhdn.astrolabe.codemode.client;

import com.github.krpkbhdn.astrolabe.codemode.BoundedLines;
import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The MCP protocol on top of a JSON-RPC transport: handshake with version negotiation, paged tools/list, tools/call. */
abstract class JsonRpcMcpClient implements McpClient {

    static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int METHOD_NOT_FOUND = -32601;
    private static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final String CLIENT_NAME = "astrolabe";
    /** Above any legitimate tool result (the container path allows 4M chars per line and the script output is capped far lower), so only a runaway or hostile server reaches it. */
    static final int MAX_RESPONSE_CHARS = 16 * 1024 * 1024;
    static final String RESPONSE_TOO_LARGE = "The server sent a response over the size limit";

    /** Far above any real server; a hostile one that pages forever or lists endless tools is cut off instead of growing memory. */
    static final int MAX_TOOL_PAGES = 50;
    static final int MAX_TOOLS = 5000;
    static final String TOO_MANY_TOOLS = "The server lists more tools than the limit allows";

    private final String server;
    private volatile String protocolVersion;

    JsonRpcMcpClient(String server) {
        this.server = server;
    }

    /** @return the {@code result} of the response, never null */
    abstract JsonNode request(String method, JsonNode params);

    abstract void notifyServer(String method);

    /** The version both sides agreed on, for transports that must repeat it; null before {@link #initialize}. */
    String protocolVersion() {
        return protocolVersion;
    }

    @Override
    public void initialize() {
        var params = MAPPER.createObjectNode().put("protocolVersion", SUPPORTED_VERSIONS.getFirst());
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", CLIENT_NAME).put("version", "1");
        var answered = request("initialize", params).path("protocolVersion").asString("");
        if (!SUPPORTED_VERSIONS.contains(answered)) {
            throw new McpClientException("The server speaks an unsupported MCP version");
        }
        protocolVersion = answered;
        notifyServer("notifications/initialized");
    }

    @Override
    public List<RemoteTool> listTools() {
        var tools = new ArrayList<RemoteTool>();
        var seen = new HashSet<String>();
        String cursor = null;
        var pages = 0;
        do {
            if (++pages > MAX_TOOL_PAGES) {
                throw new McpClientException(TOO_MANY_TOOLS);
            }
            var params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            var page = request("tools/list", params);
            page.path("tools").forEach(node -> tools.add(tool(node)));
            if (tools.size() > MAX_TOOLS) {
                throw new McpClientException(TOO_MANY_TOOLS);
            }
            var next = page.path("nextCursor").asString("");
            cursor = next.isEmpty() || !seen.add(next) ? null : next;
        } while (cursor != null);
        return List.copyOf(tools);
    }

    @Override
    public JsonNode callTool(String tool, JsonNode arguments) {
        var params = MAPPER.createObjectNode().put("name", tool);
        params.set("arguments", arguments == null || arguments.isNull() ? MAPPER.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    /** MCP defaults: a tool is read-only only when it says so and destructive unless it says otherwise; an absent hint is passed on as null. */
    private RemoteTool tool(JsonNode node) {
        var annotations = node.path("annotations");
        var outputSchema = node.path("outputSchema");
        return RemoteTool.annotated(server, node.path("name").asString(""), node.path("description").asString(""),
                node.has("inputSchema") ? node.get("inputSchema") : MAPPER.createObjectNode(),
                outputSchema.isObject() ? Optional.of(outputSchema) : Optional.empty(),
                hint(annotations, "readOnlyHint"), hint(annotations, "destructiveHint"));
    }

    private static Boolean hint(JsonNode annotations, String name) {
        return annotations.path(name).isBoolean() ? annotations.get(name).asBoolean() : null;
    }

    /** A failure without the cause's message: IO and URI errors can carry the URL, a query token or a command line. */
    McpClientException failure(Throwable cause) {
        return cause instanceof McpClientException client ? client
                : new McpClientException("The server " + server + " failed: " + cause.getClass().getSimpleName());
    }

    /** @return the next line, or null at the end of the stream; throws {@link McpClientException} past {@link #MAX_RESPONSE_CHARS} */
    static String readBoundedLine(BufferedReader in) throws IOException {
        try {
            return BoundedLines.read(in, MAX_RESPONSE_CHARS);
        } catch (BoundedLines.TooLongException e) {
            throw new McpClientException(RESPONSE_TOO_LARGE);
        }
    }

    static ObjectNode requestMessage(long id, String method, JsonNode params) {
        var message = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", params);
        return message;
    }

    static ObjectNode notificationMessage(String method) {
        return MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method);
    }

    /** The answer to a request the server sent us: ping is fine, every other capability (elicitation, sampling, roots) is declined. */
    static ObjectNode replyToServerRequest(JsonNode request) {
        var reply = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", request.get("id"));
        var method = request.path("method").asString("");
        if ("ping".equals(method)) {
            reply.putObject("result");
        } else {
            reply.putObject("error").put("code", METHOD_NOT_FOUND).put("message", "The client does not support " + method);
        }
        return reply;
    }

    /** @return the result of a response message, or throws with the server's error message */
    static JsonNode resultOf(JsonNode response) {
        var error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpServerErrorException(error.path("code").asInt(), error.path("message").asString(""));
        }
        return response.path("result");
    }
}
