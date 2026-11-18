package com.github.krpkbhdn.astrolabe.codemode.policy;

import com.github.krpkbhdn.astrolabe.review.PromptText;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the user sees of a call's arguments: secret-looking values masked, long strings and big collections cut, control and invisible characters replaced.
 * Every cut, replaced character and renamed key sets {@link Preview#truncated()}, because what is shown is then not exactly what is sent.
 */
final class ArgumentPreview {

    static final String MASK = "***";
    static final int MAX_TEXT = 200;
    static final int MAX_ITEMS = 20;
    static final int MAX_DEPTH = 6;
    static final String TOO_DEEP = "... (nested deeper than " + MAX_DEPTH + " levels)";
    static final String OVERFLOW_KEY = "...";
    private static final Pattern SECRET_NAME = Pattern.compile(
            "token|password|passwd|passphrase|secret|authorization|api[_-]?key|cookie|credential|bearer|private[_-]?key|session|jwt|signature",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_SHAPE = Pattern.compile("\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}|\\bsk-[A-Za-z0-9_-]{16,}|\\bgh[pousr]_[A-Za-z0-9]{20,}"
            + "|\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}|(?<![A-Za-z0-9_+=-])(?=[A-Za-z0-9_+=-]*\\d)(?=[A-Za-z0-9_+=-]*[A-Za-z])[A-Za-z0-9_+=-]{40,}(?![A-Za-z0-9_+=-])",
            Pattern.CASE_INSENSITIVE);
    /** Newlines and tabs in a text show as a space without counting as hidden, so a multi-line script argument stays whole. */
    private static final Pattern PLAIN_WHITESPACE = Pattern.compile("[\\r\\n\\t]");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** @param truncated true when anything was hidden */
    record Preview(JsonNode node, boolean truncated) {}

    private boolean truncated;

    private ArgumentPreview() {
    }

    static Preview of(JsonNode arguments) {
        if (arguments == null) {
            return new Preview(MAPPER.createObjectNode(), false);
        }
        var preview = new ArgumentPreview();
        var node = preview.copy(arguments, 0);
        return new Preview(node, preview.truncated);
    }

    static String moreFields(int count) {
        return count + " more fields";
    }

    static String moreItems(int count) {
        return "... " + count + " more items";
    }

    static String moreCharacters(int count) {
        return "... [" + count + " more characters]";
    }

    private JsonNode copy(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH && node.isContainer()) {
            truncated = true;
            return text(TOO_DEEP);
        }
        if (node.isObject()) {
            return copyObject(node, depth);
        }
        if (node.isArray()) {
            var out = MAPPER.createArrayNode();
            node.valueStream().limit(MAX_ITEMS).forEach(item -> out.add(copy(item, depth + 1)));
            if (node.size() > MAX_ITEMS) {
                truncated = true;
                out.add(moreItems(node.size() - MAX_ITEMS));
            }
            return out;
        }
        if (node.isString()) {
            return text(cut(SECRET_SHAPE.matcher(clean(node.stringValue())).replaceAll(MASK)));
        }
        return node;
    }

    private JsonNode copyObject(JsonNode node, int depth) {
        ObjectNode out = MAPPER.createObjectNode();
        Set<String> used = new HashSet<>(Set.of(OVERFLOW_KEY));
        node.properties().stream().limit(MAX_ITEMS).forEach(entry -> out.set(uniqueKey(cut(clean(entry.getKey())), used),
                isSecretName(entry.getKey()) ? text(MASK) : copy(entry.getValue(), depth + 1)));
        if (node.size() > MAX_ITEMS) {
            truncated = true;
            out.put(OVERFLOW_KEY, moreFields(node.size() - MAX_ITEMS));
        }
        return out;
    }

    private static boolean isSecretName(String key) {
        return SECRET_NAME.matcher(PromptText.withoutHidden(key).replace(" ", "")).find();
    }

    /** Distinct keys that look the same after cutting or cleaning must not overwrite each other, and a real key must never pass for the overflow marker. */
    private String uniqueKey(String key, Set<String> used) {
        var unique = key;
        for (int n = 2; !used.add(unique); n++) {
            truncated = true;
            unique = key + " (" + n + ")";
        }
        return unique;
    }

    private String clean(String value) {
        var clean = PromptText.withoutHidden(value);
        if (!clean.equals(PLAIN_WHITESPACE.matcher(value).replaceAll(" "))) {
            truncated = true;
        }
        return clean;
    }

    private String cut(String value) {
        if (value.length() <= MAX_TEXT) {
            return value;
        }
        truncated = true;
        return value.substring(0, MAX_TEXT) + moreCharacters(value.length() - MAX_TEXT);
    }

    private static JsonNode text(String value) {
        return MAPPER.getNodeFactory().stringNode(value);
    }
}
