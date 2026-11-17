package com.github.krpkbhdn.astrolabe.overlay;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the overlays of one session add to a launch. [roots] are the existing overlay folders (the session denies edits and reads of their config there),
 * [contextDirectories] are added with --add-dir for their skills. No roots means nothing is added.
 */
public record SessionOverlay(Map<String, JsonNode> mcpServers, ObjectNode settings, String instructions, Map<String, OverlayAgent> agents,
        List<Path> contextDirectories, List<Path> roots) {

    public static final SessionOverlay EMPTY = new SessionOverlay(Map.of(), JsonMapper.builder().build().createObjectNode(), "", Map.of(), List.of(), List.of());

    public boolean isEmpty() {
        return roots.isEmpty();
    }
}
