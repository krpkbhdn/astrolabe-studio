package com.github.krpkbhdn.astrolabe.codemode.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TypeScriptSignaturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static RemoteTool tool(String server, String name, String description, String input, String output) {
        return RemoteTool.annotated(server, name, description, json(input), Optional.ofNullable(output).map(TypeScriptSignaturesTest::json),
                false, false);
    }

    private static String declare(String input) {
        return TypeScriptSignatures.declaration(tool("s", "t", null, input, null));
    }

    @Test
    void rendersRequiredAndOptionalProperties() {
        String text = TypeScriptSignatures.declaration(tool("playwright", "browser_click", "Click an element",
                "{\"type\":\"object\",\"properties\":{\"element\":{\"type\":\"string\"},\"ref\":{\"type\":\"string\"},"
                        + "\"doubleClick\":{\"type\":\"boolean\"}},\"required\":[\"element\",\"ref\"]}", null));
        assertThat(text).isEqualTo("/** Click an element */\nservers.playwright.browser_click(args: { element: string; "
                + "ref: string; doubleClick?: boolean }): Promise<unknown>");
    }

    @Test
    void makesArgsOptionalWhenNothingIsRequired() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}"))
                .isEqualTo("servers.s.t(args?: { a?: number }): Promise<unknown>");
    }

    @Test
    void dropsArgsWithoutProperties() {
        assertThat(declare("{\"type\":\"object\"}")).isEqualTo("servers.s.t(): Promise<unknown>");
    }

    @Test
    void rendersEnumsAsLiteralUnions() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"string\",\"enum\":[\"a\",\"b\",1,null]}},"
                + "\"required\":[\"m\"]}")).contains("m: \"a\" | \"b\" | 1 | null");
    }

    @Test
    void rendersArraysAndUnionElementsInParentheses() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                + "\"y\":{\"type\":\"array\",\"items\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}}}"))
                .contains("x?: string[]").contains("y?: (string | number)[]");
    }

    @Test
    void rendersNestedObjects() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"o\":{\"type\":\"object\",\"properties\":"
                + "{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}},\"required\":[\"o\"]}"))
                .contains("o: { k: string }");
    }

    @Test
    void rendersTypeArraysAndOneOfAsUnion() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":[\"string\",\"null\"]},"
                + "\"b\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"integer\"}]}}}"))
                .contains("a?: string | null").contains("b?: boolean | number");
    }

    @Test
    void refAndUnsupportedBecomeUnknown() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"r\":{\"$ref\":\"#/$defs/R\"},\"w\":{},"
                + "\"z\":{\"type\":\"weird\"},\"u\":{\"anyOf\":[{\"type\":\"string\"},{\"$ref\":\"#\"}]}}}"))
                .contains("r?: unknown").contains("w?: unknown").contains("z?: unknown").contains("u?: unknown");
    }

    @Test
    void rendersFreeFormObjectsAsRecords() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"object\"},"
                + "\"n\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}}"))
                .contains("m?: Record<string, unknown>").contains("n?: Record<string, string>");
    }

    @Test
    void quotesPropertyNamesThatAreNotIdentifiers() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"a-b\":{\"type\":\"string\"},\"1x\":{\"type\":\"string\"}}}"))
                .contains("\"a-b\"?: string").contains("\"1x\"?: string");
    }

    @Test
    void rendersPropertyDescriptionsAndEscapesCommentEnd() {
        assertThat(declare("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\","
                + "\"description\":\"line one\\nends */ here\"}}}"))
                .contains("/** line one ends *\\/ here */ a?: string");
    }

    @Test
    void escapesCommentEndInToolDescription() {
        assertThat(TypeScriptSignatures.declaration(tool("s", "t", "evil */ x", "{}", null)))
                .startsWith("/** evil *\\/ x */\n");
    }

    @Test
    void usesOutputSchemaForResult() {
        assertThat(TypeScriptSignatures.declaration(tool("s", "t", null, "{}",
                "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"]}")))
                .isEqualTo("servers.s.t(): Promise<{ ok: boolean }>");
    }

    @Test
    void usesBracketAccessorForBothPartsWhenOneNameIsInvalid() {
        assertThat(TypeScriptSignatures.accessor("my-server", "tool")).isEqualTo("servers[\"my-server\"][\"tool\"]");
        assertThat(TypeScriptSignatures.accessor("srv", "do.it")).isEqualTo("servers[\"srv\"][\"do.it\"]");
        assertThat(TypeScriptSignatures.accessor("srv", "do_it")).isEqualTo("servers.srv.do_it");
        assertThat(TypeScriptSignatures.accessor("a\"b", "t")).isEqualTo("servers[\"a\\\"b\"][\"t\"]");
    }

    @Test
    void preludeAndRunnerReserveExactlyTheNamesTheAccessorRoutesThroughCall() throws Exception {
        for (var file : java.util.List.of("prelude.js", "runner.js")) {
            var source = new String(getClass().getResourceAsStream("/code-mode/" + file).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var matcher = java.util.regex.Pattern.compile("RESERVED_NAMES = \\[(.*?)\\]").matcher(source);
            assertThat(matcher.find()).as(file).isTrue();
            var names = java.util.Arrays.stream(matcher.group(1).split(",")).map(n -> n.strip().replace("'", "")).collect(java.util.stream.Collectors.toSet());

            assertThat(names).as(file).isEqualTo(TypeScriptSignatures.RESERVED_TOOL_NAMES);
        }
        assertThat(TypeScriptSignatures.accessor("db", "toString")).isEqualTo("servers.db.__call(\"toString\", args)");
    }

    @Test
    void reservedToolNamesAreReachedThroughCall() {
        assertThat(TypeScriptSignatures.accessor("db", "then")).isEqualTo("servers.db.__call(\"then\", args)");
        assertThat(TypeScriptSignatures.accessor("my-db", "toJSON")).isEqualTo("servers[\"my-db\"].__call(\"toJSON\", args)");
        assertThat(TypeScriptSignatures.declaration(tool("db", "then", null, "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}", null)))
                .isEqualTo("servers.db.__call(\"then\", args: { q: string }): Promise<unknown>");
        assertThat(TypeScriptSignatures.declaration(tool("db", "__call", null, "{}", null))).isEqualTo("servers.db.__call(\"__call\"): Promise<unknown>");
    }

    @Test
    void longDescriptionIsCutAndMarked() {
        String declaration = TypeScriptSignatures.declaration(tool("s", "t", "d".repeat(100_000), "{}", null));

        assertThat(declaration).contains("... (truncated) */").hasSizeLessThan(TypeScriptSignatures.MAX_COMMENT_CHARS + 100);
    }

    @Test
    void deepNestingDegradesToUnknown() {
        String schema = "{\"type\":\"string\"}";
        for (int i = 0; i < TypeScriptSignatures.MAX_DEPTH + 3; i++) {
            schema = "{\"type\":\"object\",\"properties\":{\"n\":" + schema + "}}";
        }
        assertThat(declare(schema)).contains("unknown");
    }

    @Test
    void truncatesHugeSchemas() {
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            properties.append(i == 0 ? "" : ",").append("\"property_number_").append(i).append("\":{\"type\":\"string\"}");
        }
        String text = declare("{\"type\":\"object\",\"properties\":{" + properties + "}}");
        assertThat(text).contains(TypeScriptSignatures.TRUNCATED);
        assertThat(text.length()).isLessThan(TypeScriptSignatures.MAX_TYPE_CHARS + 200);
    }

    @Test
    void summaryIsOneLine() {
        assertThat(TypeScriptSignatures.summary(tool("s", "t", "First line\nsecond", "{}", null))).isEqualTo("s.t - First line");
        assertThat(TypeScriptSignatures.summary(tool("s", "t", null, "{}", null))).isEqualTo("s.t");
        assertThat(TypeScriptSignatures.summary(tool("s", "t", "x".repeat(300), "{}", null)).length()).isLessThan(120);
    }
}
