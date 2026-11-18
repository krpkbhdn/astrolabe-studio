package com.github.krpkbhdn.astrolabe.codemode;

import java.util.ArrayList;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns the result of a remote tools/call into the JSON text a script receives: structured content, else parsed text, with images saved as files. */
final class ToolResults {

    static final int MAX_ERROR_CHARS = 2000;
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ToolResults() {
    }

    /** @throws IllegalStateException with the tool's own message when the result is flagged as an error */
    static String toScriptValue(JsonNode result, SessionImages images) {
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException(errorText(result));
        }
        var structured = result.path("structuredContent");
        if (!structured.isMissingNode() && !structured.isNull()) {
            return structured.toString();
        }
        var parts = new ArrayList<JsonNode>();
        for (var block : result.path("content")) {
            switch (block.path("type").asString("")) {
                case "text" -> parts.add(textValue(block.path("text").asString("")));
                case "image" -> parts.add(images.store(block.path("mimeType").asString(""), block.path("data").asString("")));
                default -> { }
            }
        }
        return switch (parts.size()) {
            case 0 -> "null";
            case 1 -> parts.getFirst().toString();
            default -> JSON.createArrayNode().addAll(parts).toString();
        };
    }

    private static JsonNode textValue(String text) {
        var stripped = text.strip();
        if (stripped.startsWith("{") || stripped.startsWith("[")) {
            try {
                return JSON.readTree(stripped);
            } catch (RuntimeException e) {
                return JSON.getNodeFactory().stringNode(text);
            }
        }
        return JSON.getNodeFactory().stringNode(text);
    }

    private static String errorText(JsonNode result) {
        var text = new StringBuilder();
        result.path("content").forEach(block -> {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString("")).append('\n');
            }
        });
        var message = text.isEmpty() ? "The tool reported an error" : text.toString().strip();
        return message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) + "..." : message;
    }
}
