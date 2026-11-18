package com.github.krpkbhdn.astrolabe.extension.mcp;

import java.util.Set;
import java.util.regex.Pattern;

/** Which server names may appear in a permission rule or be used as a script alias: a hostile configuration cannot inject a rule through its name. */
public final class McpServerNames {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Set<String> RESERVED = Set.of("astrolabe", "astrolabe-code");

    private McpServerNames() {}

    public static boolean usable(String name) {
        return name != null && SAFE_NAME.matcher(name).matches() && !RESERVED.contains(name);
    }
}
