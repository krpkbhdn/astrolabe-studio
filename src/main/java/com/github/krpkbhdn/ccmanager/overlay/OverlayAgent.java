package com.github.krpkbhdn.ccmanager.overlay;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Claude subagent from an overlay's agents folder. [tools] is the comma separated list of the frontmatter, null for all tools;
 * [markdown] is the file as written, for the plugin fallback.
 */
public record OverlayAgent(String name, String description, String prompt, String model, String tools, String markdown) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String FENCE = "---";
    private static final String MARKDOWN_SUFFIX = ".md";

    /** @return empty when the name (frontmatter, else the file name) is not a plain identifier */
    public static Optional<OverlayAgent> parse(String fileName, String markdown) {
        var text = markdown.replace("\r\n", "\n");
        var header = new HashMap<String, String>();
        var body = text;
        if (text.startsWith(FENCE + "\n")) {
            var end = text.indexOf("\n" + FENCE, FENCE.length());
            if (end > 0) {
                readHeader(text.substring(FENCE.length() + 1, end), header);
                body = text.substring(Math.min(text.length(), end + FENCE.length() + 1)).stripLeading();
            }
        }
        var name = header.getOrDefault("name", fileName.endsWith(MARKDOWN_SUFFIX) ? fileName.substring(0, fileName.length() - MARKDOWN_SUFFIX.length()) : fileName);
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        var tools = header.get("tools");
        return Optional.of(new OverlayAgent(name, header.getOrDefault("description", ""), body.strip(), header.get("model"),
                tools == null ? null : tools.replaceAll("^\\[|]$", ""), markdown));
    }

    /** An indented line continues the previous key (folded, literal or wrapped values are joined with single spaces), so a long description is never cut. */
    private static void readHeader(String header, Map<String, String> into) {
        var values = new LinkedHashMap<String, StringBuilder>();
        StringBuilder current = null;
        for (var line : header.split("\n")) {
            var colon = line.indexOf(':');
            if (!line.isBlank() && Character.isWhitespace(line.charAt(0)) && current != null) {
                current.append(' ').append(line.strip());
            } else if (colon > 0) {
                current = new StringBuilder(line.substring(colon + 1).strip());
                values.put(line.substring(0, colon).strip(), current);
            }
        }
        values.forEach((key, value) -> into.put(key, value.toString().strip().replaceAll("^[>|][+-]?\\s*", "").replaceAll("^([\"'])(.*)\\1$", "$2")));
    }
}
