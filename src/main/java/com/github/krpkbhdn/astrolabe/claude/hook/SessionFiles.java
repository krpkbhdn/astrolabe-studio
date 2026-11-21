package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.overlay.OverlayAgent;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * The per-session files handed to claude: --settings and --mcp-config, and what the project overlay adds: [promptFile] (null unless overlay
 * instructions exist; it then also carries the team addendum), [agentsPlugin] (null unless the overlay agents were too long for --agents),
 * [skillsPlugin] (null unless the overlay has skills) and the [overlay] itself.
 */
public record SessionFiles(Path settings, Path mcpConfig, Path promptFile, Path agentsPlugin, Path skillsPlugin, SessionOverlay overlay) {

    public SessionFiles(Path settings, Path mcpConfig) {
        this(settings, mcpConfig, null, null, null, SessionOverlay.EMPTY);
    }

    public SessionFiles(Path settings, Path mcpConfig, Path promptFile, Path agentsPlugin, SessionOverlay overlay) {
        this(settings, mcpConfig, promptFile, agentsPlugin, null, overlay);
    }

    /** The overlay agents that go into --agents. */
    public Collection<OverlayAgent> inlineAgents() {
        return agentsPlugin == null ? overlay.agents().values() : List.of();
    }
}
