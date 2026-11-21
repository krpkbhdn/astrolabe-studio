package com.github.krpkbhdn.astrolabe.overlay;

import com.github.krpkbhdn.astrolabe.extension.Json;
import com.github.krpkbhdn.astrolabe.extension.mcp.ProjectMcpServers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The app's config folder of one project, outside the project's repository: where its files live, an owner-only writer and a tolerant reader. */
public record ProjectOverlay(Path root) {

    private static final Logger log = LoggerFactory.getLogger(ProjectOverlay.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String MCP_FILE = "mcp.json";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String INSTRUCTIONS_FILE = "instructions.md";
    private static final String AGENTS_DIRECTORY = "agents";
    private static final String CONTEXT_DIRECTORY = "context";
    /** Below a context folder. */
    public static final String SKILLS_DIRECTORY = ".claude/skills";
    private static final String SERVERS = "mcpServers";

    public ProjectOverlay {
        root = root.toAbsolutePath().normalize();
    }

    public Path mcpFile() {
        return root.resolve(MCP_FILE);
    }

    public Path settingsFile() {
        return root.resolve(SETTINGS_FILE);
    }

    public Path instructionsFile() {
        return root.resolve(INSTRUCTIONS_FILE);
    }

    public Path agentsDirectory() {
        return root.resolve(AGENTS_DIRECTORY);
    }

    public Path contextDirectory() {
        return root.resolve(CONTEXT_DIRECTORY);
    }

    /** @throws IllegalArgumentException when [file] lies outside the overlay folder */
    public void write(Path file, String content) throws IOException {
        var target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Not inside the project overlay: " + file);
        }
        OwnerOnlyFiles.createDirectory(target.getParent());
        OwnerOnlyFiles.write(target, content);
    }

    /** [memberDirectory] makes relative server paths absolute (a group member's servers start in the group folder); null leaves them as written. An unreadable file is skipped, never fails the launch. */
    public SessionOverlay read(Path memberDirectory) {
        var servers = servers(memberDirectory);
        var settings = settings();
        var instructions = text(instructionsFile());
        var agents = agents();
        var context = Files.isDirectory(contextDirectory().resolve(SKILLS_DIRECTORY)) ? List.of(contextDirectory()) : List.<Path>of();
        if (!Files.isDirectory(root)) {
            return SessionOverlay.EMPTY;
        }
        return new SessionOverlay(servers, settings, instructions, agents, context, List.of(root));
    }

    private Map<String, JsonNode> servers(Path memberDirectory) {
        var servers = new LinkedHashMap<String, JsonNode>();
        readObjectLenient(mcpFile()).path(SERVERS).properties().stream().filter(entry -> entry.getValue() instanceof ObjectNode).forEach(entry -> {
            var definition = (ObjectNode) entry.getValue().deepCopy();
            servers.put(entry.getKey(), memberDirectory == null ? definition : ProjectMcpServers.absolutized(definition, memberDirectory));
        });
        return servers;
    }

    private ObjectNode settings() {
        return readObjectLenient(settingsFile());
    }

    private Map<String, OverlayAgent> agents() {
        var agents = new LinkedHashMap<String, OverlayAgent>();
        if (!Files.isDirectory(agentsDirectory())) {
            return agents;
        }
        try (var files = Files.list(agentsDirectory())) {
            var sorted = new ArrayList<>(files.filter(file -> file.getFileName().toString().endsWith(".md") && Files.isRegularFile(file)).sorted().toList());
            sorted.forEach(file -> OverlayAgent.parse(file.getFileName().toString(), text(file)).ifPresent(agent -> agents.putIfAbsent(agent.name(), agent)));
        } catch (IOException e) {
            log.warn("Overlay agents of {} skipped: {}", root.getFileName(), e.getClass().getSimpleName());
        }
        return agents;
    }

    /**
     * The one reader of the overlay's JSON files: an empty object for a missing or blank file.
     *
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when it is not a JSON object
     */
    public ObjectNode readObject(Path file) throws IOException {
        var content = Files.isRegularFile(file) ? Files.readString(file) : "";
        if (content.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            if (Json.parse(content) instanceof ObjectNode object) {
                return object;
            }
        } catch (RuntimeException e) {
            // reported below as not an object
        }
        throw new IllegalArgumentException(file.getFileName() + " is not a JSON object");
    }

    /** Like {@link #readObject}, but an unusable file reads as empty (and is logged): a launch never fails for it. */
    public ObjectNode readObjectLenient(Path file) {
        try {
            return readObject(file);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Overlay file {} skipped: {}", file.getFileName(), e.getClass().getSimpleName());
            return MAPPER.createObjectNode();
        }
    }

    private static String text(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            log.warn("Overlay file {} skipped: {}", file.getFileName(), e.getClass().getSimpleName());
            return "";
        }
    }
}
