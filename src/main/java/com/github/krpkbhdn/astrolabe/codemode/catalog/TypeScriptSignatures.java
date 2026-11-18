package com.github.krpkbhdn.astrolabe.codemode.catalog;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Renders MCP tool schemas as TypeScript declarations; pure, schemas that are deep, huge or use $ref degrade to unknown. */
public final class TypeScriptSignatures {

    static final int MAX_DEPTH = 6;
    static final int MAX_TYPE_CHARS = 4000;
    static final String TRUNCATED = "/* truncated */";
    static final int MAX_COMMENT_CHARS = 1000;
    private static final int SUMMARY_CHARS = 100;
    /** Names the sandbox proxy never exposes as properties; the tool is then reached through {@code __call}. */
    public static final Set<String> RESERVED_TOOL_NAMES = Set.of("then", "toJSON", "inspect", "toString", "valueOf", "toLocaleString", "constructor",
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "__proto__", "__call");
    private static final String UNKNOWN = "unknown";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private TypeScriptSignatures() {
    }

    /**
     * The expression that reaches a tool from the sandbox prelude: {@code servers.server.tool} when both names are valid
     * JS identifiers, otherwise {@code servers["server"]["tool"]} for both parts; a tool the proxy reserves (then, toJSON,
     * toString, __call, ...) is {@code servers.server.__call("tool", args)}. The prelude must follow this rule.
     */
    public static String accessor(String server, String tool) {
        if (RESERVED_TOOL_NAMES.contains(tool)) {
            return serverAccessor(server) + ".__call(" + quote(tool) + ", args)";
        }
        if (isIdentifier(server) && isIdentifier(tool)) {
            return "servers." + server + "." + tool;
        }
        return "servers[" + quote(server) + "][" + quote(tool) + "]";
    }

    private static String serverAccessor(String server) {
        return isIdentifier(server) ? "servers." + server : "servers[" + quote(server) + "]";
    }

    /** One declaration: JSDoc with the description, the accessor, the argument object type and the result type. */
    public static String declaration(RemoteTool tool) {
        String arguments = argumentList(tool.inputSchema());
        String result = tool.outputSchema().map(schema -> render(schema, 0)).orElse(UNKNOWN);
        String callee = RESERVED_TOOL_NAMES.contains(tool.name())
                ? serverAccessor(tool.server()) + ".__call(" + quote(tool.name()) + (arguments.isEmpty() ? "" : ", " + arguments) + ")"
                : accessor(tool.server(), tool.name()) + "(" + arguments + ")";
        String head = callee + ": Promise<" + cap(result) + ">";
        return comment(tool.description()).map(c -> c + "\n" + head).orElse(head);
    }

    /** {@code server.tool - first line of the description}, for browsing. */
    public static String summary(RemoteTool tool) {
        String name = tool.server() + "." + tool.name();
        String description = tool.description() == null ? "" : tool.description().strip();
        int lineEnd = description.indexOf('\n');
        String firstLine = lineEnd < 0 ? description : description.substring(0, lineEnd).strip();
        if (firstLine.length() > SUMMARY_CHARS) {
            firstLine = firstLine.substring(0, SUMMARY_CHARS) + "...";
        }
        return firstLine.isEmpty() ? name : name + " - " + firstLine;
    }

    private static String argumentList(JsonNode schema) {
        if (schema == null || !schema.isObject() || !schema.path("properties").isObject()
                || schema.path("properties").isEmpty()) {
            return "";
        }
        boolean noneRequired = !schema.path("required").isArray() || schema.path("required").isEmpty();
        return "args" + (noneRequired ? "?" : "") + ": " + cap(render(schema, 0));
    }

    private static String cap(String type) {
        return type.length() <= MAX_TYPE_CHARS ? type : type.substring(0, MAX_TYPE_CHARS) + TRUNCATED;
    }

    private static String render(JsonNode schema, int depth) {
        if (schema == null || !schema.isObject() || depth > MAX_DEPTH || schema.has("$ref")) {
            return UNKNOWN;
        }
        if (schema.path("enum").isArray()) {
            return union(literals(schema.path("enum")));
        }
        if (schema.has("const")) {
            return literal(schema.get("const"));
        }
        for (String key : List.of("anyOf", "oneOf")) {
            if (schema.path(key).isArray()) {
                List<String> members = new ArrayList<>();
                schema.path(key).forEach(member -> members.add(render(member, depth + 1)));
                return union(members);
            }
        }
        JsonNode type = schema.get("type");
        if (type != null && type.isArray()) {
            List<String> members = new ArrayList<>();
            type.forEach(name -> members.add(renderTyped(name.asString(""), schema, depth)));
            return union(members);
        }
        if (type != null && type.isString()) {
            return renderTyped(type.asString(), schema, depth);
        }
        return schema.path("properties").isObject() ? renderObject(schema, depth) : UNKNOWN;
    }

    private static String renderTyped(String type, JsonNode schema, int depth) {
        return switch (type) {
            case "string" -> "string";
            case "number", "integer" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "null";
            case "array" -> arrayOf(render(schema.get("items"), depth + 1));
            case "object" -> renderObject(schema, depth);
            default -> UNKNOWN;
        };
    }

    private static String arrayOf(String element) {
        return element.contains(" | ") ? "(" + element + ")[]" : element + "[]";
    }

    private static String renderObject(JsonNode schema, int depth) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            JsonNode additional = schema.get("additionalProperties");
            String value = additional != null && additional.isObject() ? render(additional, depth + 1) : UNKNOWN;
            return "Record<string, " + value + ">";
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(name -> required.add(name.asString("")));
        List<String> members = new ArrayList<>();
        for (String name : properties.propertyNames()) {
            JsonNode property = properties.get(name);
            String member = name(name) + (required.contains(name) ? "" : "?") + ": " + render(property, depth + 1);
            String doc = comment(property.path("description").asString("")).map(c -> c + " ").orElse("");
            members.add(doc + member);
        }
        return "{ " + String.join("; ", members) + " }";
    }

    private static List<String> literals(JsonNode values) {
        List<String> literals = new ArrayList<>();
        values.forEach(value -> literals.add(literal(value)));
        return literals;
    }

    private static String literal(JsonNode value) {
        if (value.isString()) {
            return quote(value.asString());
        }
        return value.isNumber() || value.isBoolean() || value.isNull() ? value.toString() : UNKNOWN;
    }

    private static String union(List<String> members) {
        Set<String> distinct = new LinkedHashSet<>(members);
        if (distinct.isEmpty() || distinct.contains(UNKNOWN)) {
            return UNKNOWN;
        }
        return String.join(" | ", distinct);
    }

    private static Optional<String> comment(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String flat = text.strip().replaceAll("\\s+", " ").replace("*/", "*\\/");
        String capped = flat.length() <= MAX_COMMENT_CHARS ? flat : flat.substring(0, MAX_COMMENT_CHARS) + "... (truncated)";
        return Optional.of("/** " + capped + " */");
    }

    private static String name(String property) {
        return isIdentifier(property) ? property : quote(property);
    }

    private static boolean isIdentifier(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                default -> quoted.append(c < ' ' ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return quoted.append('"').toString();
    }
}
