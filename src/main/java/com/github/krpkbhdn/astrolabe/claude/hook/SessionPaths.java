package com.github.krpkbhdn.astrolabe.claude.hook;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Where the per-session files live; every app process uses its own [hooksDir] named by its pid, so instances never touch each other's files. */
record SessionPaths(Path hooksRoot, Path hooksDir) {

    private static final String FILE_SUFFIX = ".json";
    private static final String MCP_SUFFIX = ".mcp.json";
    private static final String TOKEN_SUFFIX = ".token";
    private static final String PROMPT_SUFFIX = ".prompt.md";
    private static final String AGENTS_PLUGIN_SUFFIX = ".agents";
    private static final String SKILLS_PLUGIN_SUFFIX = ".skills";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    Path fileOf(String astrolabeSessionId) {
        if (!SAFE_ID.matcher(astrolabeSessionId).matches()) {
            throw new IllegalArgumentException("Invalid session id");
        }
        return hooksDir.resolve(astrolabeSessionId + FILE_SUFFIX);
    }

    Path mcpFileOf(String astrolabeSessionId) {
        return hooksDir.resolve(astrolabeSessionId + MCP_SUFFIX);
    }

    Path tokenFileOf(String astrolabeSessionId) {
        return hooksDir.resolve(astrolabeSessionId + TOKEN_SUFFIX).toAbsolutePath();
    }

    Path promptFileOf(String astrolabeSessionId) {
        return hooksDir.resolve(astrolabeSessionId + PROMPT_SUFFIX).toAbsolutePath();
    }

    Path agentsPluginOf(String astrolabeSessionId) {
        return hooksDir.resolve(astrolabeSessionId + AGENTS_PLUGIN_SUFFIX).toAbsolutePath();
    }

    Path skillsPluginOf(String astrolabeSessionId) {
        return hooksDir.resolve(astrolabeSessionId + SKILLS_PLUGIN_SUFFIX).toAbsolutePath();
    }
}
