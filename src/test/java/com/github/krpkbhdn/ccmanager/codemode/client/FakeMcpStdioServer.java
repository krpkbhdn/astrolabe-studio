package com.github.krpkbhdn.ccmanager.codemode.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A tiny MCP server over stdio for the client tests: two tool pages, an error tool, an elicitation tool and a tool that never answers. */
public final class FakeMcpStdioServer {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FakeMcpStdioServer() {}

    public static void main(String[] args) throws IOException {
        var in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            var message = MAPPER.readTree(line);
            if (message.has("method") && message.has("id")) {
                handle(message, in);
            }
        }
    }

    private static void handle(JsonNode request, BufferedReader in) throws IOException {
        var reply = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", request.get("id"));
        var params = request.path("params");
        switch (request.get("method").asString()) {
            case "initialize" -> {
                if (System.getenv("FAKE_REQUEST_DURING_INIT") != null) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":901,\"method\":\"ping\"}");
                    in.readLine();
                }
                reply.putObject("result").put("protocolVersion", params.path("protocolVersion").asString()).putObject("serverInfo");
            }
            case "tools/list" -> reply.set("result", toolPage(params.path("cursor").asString("")));
            case "tools/call" -> {
                var name = params.path("name").asString();
                if ("never".equals(name)) {
                    markReceived();
                    return;
                }
                if ("fail".equals(name)) {
                    reply.putObject("error").put("code", -32000).put("message", "boom");
                } else {
                    reply.set("result", callResult(name, params, in));
                }
            }
            default -> reply.putObject("error").put("code", -32601).put("message", "unknown");
        }
        System.out.println(MAPPER.writeValueAsString(reply));
        if ("stopreading".equals(params.path("name").asString())) {
            sleepForever();
        }
    }

    /** Tells the test the call arrived: creates the file named by FAKE_NEVER_FLAG. */
    private static void markReceived() throws IOException {
        var flag = System.getenv("FAKE_NEVER_FLAG");
        if (flag != null) {
            java.nio.file.Files.writeString(Path.of(flag), "");
        }
    }

    /** Stops reading stdin so the client's writes fill the pipe and block. */
    private static void sleepForever() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ObjectNode toolPage(String cursor) {
        var result = MAPPER.createObjectNode();
        var tools = result.putArray("tools");
        if (cursor.isEmpty()) {
            var plain = tools.addObject().put("name", "plain").put("description", "no annotations");
            plain.putObject("inputSchema").put("type", "object");
            result.put("nextCursor", "page2");
        } else if ("page2".equals(cursor)) {
            var read = tools.addObject().put("name", "read").put("description", "annotated");
            read.putObject("inputSchema").put("type", "object");
            read.putObject("outputSchema").put("type", "object");
            read.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false);
            result.put("nextCursor", "page3");
        } else {
            tools.addObject().put("name", "only_read_only").putObject("annotations").put("readOnlyHint", true);
            tools.addObject().put("name", "contradictory").putObject("annotations").put("readOnlyHint", true).put("destructiveHint", true);
            tools.addObject().put("name", "only_destructive_false").putObject("annotations").put("destructiveHint", false);
        }
        return result;
    }

    private static ObjectNode callResult(String name, JsonNode params, BufferedReader in) throws IOException {
        var text = params.path("arguments").toString();
        if ("elicit".equals(name)) {
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":900,\"method\":\"elicitation/create\",\"params\":{}}");
            text = "declined " + MAPPER.readTree(in.readLine()).path("error").path("code").asInt();
        }
        if ("huge".equals(name)) {
            text = "x".repeat(JsonRpcMcpClient.MAX_RESPONSE_CHARS);
        } else if ("cwd".equals(name)) {
            text = Path.of("").toAbsolutePath().toString();
        }
        var result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        return result;
    }
}
