package com.github.krpkbhdn.ccmanager.codemode.catalog;

import com.github.krpkbhdn.ccmanager.codemode.RemoteTool;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Ranks tools for a query: exact name, name prefix, name token, server token, description token; stable on ties. */
public final class ToolSearch {

    private static final int EXACT_NAME = 1000;
    private static final int NAME_PREFIX = 500;
    private static final int NAME_TOKEN = 100;
    private static final int NAME_PART = 50;
    private static final int SERVER_TOKEN = 20;
    private static final int DESCRIPTION_TOKEN = 10;

    private ToolSearch() {
    }

    /** Signatures of the best {@code limit} matches, or for a blank query one summary line per tool. */
    public static String search(Collection<RemoteTool> tools, String query, int limit) {
        if (query == null || query.isBlank()) {
            return tools.stream().map(TypeScriptSignatures::summary).collect(Collectors.joining("\n"));
        }
        return rank(tools, query, limit).stream().map(TypeScriptSignatures::declaration)
                .collect(Collectors.joining("\n\n"));
    }

    /** The best {@code limit} tools with a positive score, best first. */
    public static List<RemoteTool> rank(Collection<RemoteTool> tools, String query, int limit) {
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<String> tokens = Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+")).filter(t -> !t.isEmpty()).toList();
        return tools.stream().map(tool -> new Scored(tool, score(tool, normalized, tokens))).filter(s -> s.score > 0)
                .sorted(Comparator.comparingInt((Scored s) -> s.score).reversed()).limit(Math.max(limit, 0))
                .map(Scored::tool).toList();
    }

    private static int score(RemoteTool tool, String query, List<String> tokens) {
        String name = tool.name().toLowerCase(Locale.ROOT);
        String server = tool.server().toLowerCase(Locale.ROOT);
        String description = tool.description() == null ? "" : tool.description().toLowerCase(Locale.ROOT);
        List<String> nameTokens = Arrays.asList(name.split("[^\\p{L}\\p{N}]+"));
        int score = name.equals(query) ? EXACT_NAME : name.startsWith(query) ? NAME_PREFIX : 0;
        for (String token : tokens) {
            score += nameTokens.contains(token) ? NAME_TOKEN : name.contains(token) ? NAME_PART : 0;
            score += server.equals(token) ? SERVER_TOKEN : 0;
            score += description.contains(token) ? DESCRIPTION_TOKEN : 0;
        }
        return score;
    }

    private record Scored(RemoteTool tool, int score) {
    }
}
