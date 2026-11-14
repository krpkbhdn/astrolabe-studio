package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.codemode.ServerCandidates.Candidate;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** The names scripts use for a session's servers: which are allowed, how a spelling resolves to an alias, and the index handed to the sandbox. */
final class ServerAliases {

    static final String CLAUDE_PREFIX = "mcp__";
    static final String NAME_NOT_ALLOWED = "name not allowed in Code Mode: use letters, digits, - and _";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ServerAliases() {
    }

    /** A name that cannot be confused with a Claude tool prefix or a cc-manager server, and is safe in a permission rule. */
    static boolean allowed(String name) {
        return McpServerNames.usable(name) && !name.startsWith(CLAUDE_PREFIX);
    }

    static List<String> eligible(List<Candidate> found) {
        return found.stream().filter(candidate -> candidate.problem() == null).map(Candidate::name).toList();
    }

    /** @param name the script alias, the saved name (plugin_playwright_playwright) or a Claude tool prefix (mcp__plugin_playwright_playwright__tool) */
    static Optional<String> resolve(List<Candidate> found, String name) {
        var wanted = name.startsWith(CLAUDE_PREFIX) ? name.substring(CLAUDE_PREFIX.length()).split("__", 2)[0] : name;
        return found.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .or(() -> found.stream().filter(candidate -> candidate.name().equals(wanted)).findFirst())
                .or(() -> found.stream().filter(candidate -> candidate.stored().equals(wanted)).findFirst())
                .map(Candidate::name);
    }

    static IllegalStateException unknown(List<Candidate> found, String name) {
        return new IllegalStateException("Unknown server '" + ServerNames.shown(name) + "'. Available in Code Mode: "
                + ServerNames.listed(eligible(found)) + ". Use list_servers.");
    }

    /** Every accepted spelling (alias or saved name) mapped to its alias, and the sanitised alias list for error texts. */
    static String indexJson(List<Candidate> found) {
        var names = new LinkedHashMap<String, String>();
        found.forEach(candidate -> {
            names.put(candidate.name(), candidate.name());
            names.putIfAbsent(candidate.stored(), candidate.name());
        });
        var index = JSON.createObjectNode();
        index.set("names", JSON.valueToTree(names));
        index.put("available", ServerNames.listed(eligible(found)));
        return index.toString();
    }
}
