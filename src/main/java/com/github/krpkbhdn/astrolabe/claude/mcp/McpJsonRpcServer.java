package com.github.krpkbhdn.astrolabe.claude.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateless MCP server core (JSON-RPC 2.0 over streamable HTTP POST) shared by the project and the app server.
 * Each subclass is handed only its own tools and decides how a tool is called.
 */
public abstract class McpJsonRpcServer<T extends McpToolDescriptor> {

    static final List<String> SUPPORTED_VERSIONS = List.of("2025-03-26", "2025-06-18", "2025-11-25");
    static final String SERVER_VERSION = "1.0.0";
    private static final Logger log = LoggerFactory.getLogger(McpJsonRpcServer.class);
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    /** Runs a tool for one request; throws IllegalArgumentException or IllegalStateException with a message Claude can act on. */
    @FunctionalInterface
    protected interface ToolCall<T> {
        String call(T tool, JsonNode arguments);
    }

    protected interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /** Lets a server cancel a running tool call when the client sends {@code notifications/cancelled}; ids are JSON-encoded (1 and "1" differ). */
    protected interface Tracking {
        Tracking NONE = new Tracking() {
            @Override
            public Handle started(String toolName, String requestId) {
                return () -> { };
            }

            @Override
            public void cancelled(String requestId) {
            }
        };

        /** @return closed when the call ends */
        Handle started(String toolName, String requestId);

        void cancelled(String requestId);
    }

    private final Map<String, T> tools = new LinkedHashMap<>();
    private final JsonMapper mapper;
    private final String serverName;
    private final String instructions;

    protected McpJsonRpcServer(List<T> tools, JsonMapper mapper, String serverName, String instructions) {
        tools.forEach(tool -> {
            if (this.tools.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool name: " + tool.name());
            }
        });
        this.mapper = mapper;
        this.serverName = serverName;
        this.instructions = instructions;
    }

    /** @return the JSON response, empty for a notification */
    protected final Optional<String> respond(byte[] body, ToolCall<T> toolCall) {
        return respond(body, toolCall, () -> instructions);
    }

    /** @param initializeInstructions computed at each initialize, for a server whose instructions depend on the session */
    protected final Optional<String> respond(byte[] body, ToolCall<T> toolCall, Supplier<String> initializeInstructions) {
        return respond(body, toolCall, initializeInstructions, Tracking.NONE);
    }

    protected final Optional<String> respond(byte[] body, ToolCall<T> toolCall, Supplier<String> initializeInstructions, Tracking tracking) {
        JsonNode request;
        try {
            request = mapper.readTree(body);
            if (request.isMissingNode()) {
                return Optional.of(error(null, PARSE_ERROR, "Parse error"));
            }
        } catch (RuntimeException e) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error"));
        }
        var id = request.get("id");
        log.debug("MCP JSON-RPC {} (id {})", request.path("method").asString(""), id);
        if (!request.isObject() || !request.path("method").isString()) {
            return id == null && request.isObject() ? Optional.empty() : Optional.of(error(id, INVALID_REQUEST, "Invalid request"));
        }
        if (id == null) {
            var requestId = request.path("params").get("requestId");
            if ("notifications/cancelled".equals(request.path("method").asString()) && requestId != null && (requestId.isNumber() || requestId.isString())) {
                tracking.cancelled(requestId.toString());
            }
            return Optional.empty();
        }
        try {
            var response = mapper.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", dispatch(request.path("method").asString(), request.path("params"), toolCall, initializeInstructions, id, tracking));
            return Optional.of(mapper.writeValueAsString(response));
        } catch (RpcException e) {
            return Optional.of(error(id, e.code, e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("MCP request {} failed: {}", request.path("method").asString(""), e.getClass().getSimpleName());
            log.debug("MCP request failure detail", e);
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error"));
        }
    }

    private JsonNode dispatch(String method, JsonNode params, ToolCall<T> toolCall, Supplier<String> initializeInstructions, JsonNode id, Tracking tracking) {
        return switch (method) {
            case "initialize" -> initialize(params, initializeInstructions);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> listTools();
            case "tools/call" -> callTool(params, toolCall, id, tracking);
            default -> throw new RpcException(METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private ObjectNode initialize(JsonNode params, Supplier<String> initializeInstructions) {
        var requested = params.path("protocolVersion").asString("");
        var result = mapper.createObjectNode();
        result.put("protocolVersion", SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.getLast());
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", serverName).put("version", SERVER_VERSION);
        result.put("instructions", initializeInstructions.get());
        return result;
    }

    private ObjectNode listTools() {
        var result = mapper.createObjectNode();
        var list = result.putArray("tools");
        tools.values().forEach(tool -> {
            var entry = list.addObject().put("name", tool.name()).put("description", tool.description());
            entry.set("inputSchema", mapper.readTree(tool.inputSchema()));
        });
        return result;
    }

    private ObjectNode callTool(JsonNode params, ToolCall<T> toolCall, JsonNode id, Tracking tracking) {
        var tool = tools.get(params.path("name").asString(""));
        if (tool == null) {
            throw new RpcException(INVALID_PARAMS, "Unknown tool: " + params.path("name").asString(""));
        }
        try (var ignored = tracking.started(tool.name(), id.toString())) {
            return toolResult(toolCall.call(tool, params.path("arguments")), false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return toolResult(e.getMessage(), true);
        } catch (RuntimeException e) {
            log.warn("MCP tool {} failed: {}", tool.name(), e.getClass().getSimpleName());
            log.debug("MCP tool failure detail", e);
            return toolResult("astrolabe could not run " + tool.name() + ": " + e.getClass().getSimpleName(), true);
        }
    }

    private ObjectNode toolResult(String text, boolean isError) {
        var result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    private String error(JsonNode id, int code, String message) {
        var response = mapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return mapper.writeValueAsString(response);
    }

    private static final class RpcException extends RuntimeException {
        private final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
