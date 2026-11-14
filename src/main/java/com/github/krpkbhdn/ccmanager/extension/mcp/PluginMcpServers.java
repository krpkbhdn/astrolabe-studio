package com.github.krpkbhdn.ccmanager.extension.mcp;

import static com.github.krpkbhdn.ccmanager.extension.Json.text;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.Json;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP servers of the enabled Claude Code plugins for a project, read from the plugin's .mcp.json and the {@code mcpServers} of its plugin.json, never beyond the plugin folder.
 * Read-only apart from the plugin data folder; a malformed entry only drops itself. Definitions hold expanded secrets: never log them.
 */
@Component
public class PluginMcpServers {

    /** @param plugin the plugin name without marketplace; @param key the server's name inside the plugin; the definition is named {@link #canonicalName()} */
    public record PluginServer(String plugin, String key, McpServerDefinition definition) {

        /** Claude's own spelling of this server, as in {@code mcp__plugin_<plugin>_<server>__<tool>}: usable as a tool-name prefix. */
        public String canonicalName() {
            return definition.name();
        }

        /** How {@code claude mcp list} names it. */
        public String listedName() {
            return "plugin:" + plugin + ":" + key;
        }
    }

    private record Install(Path path, String scope, String projectPath, String updated) {
    }

    private record Chosen(Path path, boolean projectInstalled) {
    }

    private record Settings(JsonNode user, List<JsonNode> repository, JsonNode managed) {
    }

    private static final Logger log = LoggerFactory.getLogger(PluginMcpServers.class);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_-]");
    private static final String SERVERS = "mcpServers";
    private static final String MANIFEST = ".claude-plugin/plugin.json";
    private static final String ORPHAN_MARKER = ".orphaned_at";
    private static final String DATA_VARIABLE = "CLAUDE_PLUGIN_DATA";
    private static final String USER_SCOPE = "user";
    private static final List<String> SCOPE_PRECEDENCE = List.of(USER_SCOPE, "project", "local");

    private final HostEnvironment host;
    private final McpConfigReader reader;

    PluginMcpServers(HostEnvironment host, McpConfigReader reader) {
        this.host = host;
        this.reader = reader;
    }

    /** Characters Claude does not allow in a tool name become {@code _}. */
    public static String canonicalName(String plugin, String key) {
        return UNSAFE.matcher("plugin_" + plugin + "_" + key).replaceAll("_");
    }

    public List<PluginServer> servers(Path projectDirectory) {
        return servers(projectDirectory, projectDirectory);
    }

    /** @param directory the session's working directory (a task worktree for isolated sessions); @param projectRoot the registered project folder, which project and local installs may name instead */
    public List<PluginServer> servers(Path directory, Path projectRoot) {
        var settings = settings(directory);
        var found = new ArrayList<PluginServer>();
        installs(directory, projectRoot).forEach((id, install) -> {
            if (id.indexOf('@') > 0 && enabled(settings, id, install.projectInstalled())) {
                try {
                    found.addAll(serversOf(id, install.path(), directory));
                } catch (RuntimeException e) {
                    log.warn("The MCP servers of plugin {} are unreadable, skipped", id);
                }
            }
        });
        return List.copyOf(found);
    }

    private List<PluginServer> serversOf(String id, Path install, Path directory) {
        var plugin = id.substring(0, id.indexOf('@'));
        var data = dataDirectory(id);
        var variables = Map.of("CLAUDE_PLUGIN_ROOT", install.toString(), DATA_VARIABLE, data.toString(),
                "CLAUDE_PROJECT_DIR", directory == null ? "${CLAUDE_PROJECT_DIR}" : directory.toString());
        var declared = new LinkedHashMap<String, JsonNode>();
        collect(readInside(install, ".mcp.json"), declared);
        var manifest = readInside(install, MANIFEST).path(SERVERS);
        (manifest.isArray() ? manifest.valueStream().toList() : List.of(manifest)).forEach(entry -> {
            if (entry.isString()) {
                if (entry.asString("").endsWith(".json")) {
                    collect(readInside(install, entry.asString("")), declared);
                }
            } else {
                collect(entry, declared);
            }
        });
        if (declared.values().stream().anyMatch(node -> node.toString().contains(DATA_VARIABLE))) {
            createDirectory(data);
        }
        return declared.entrySet().stream().map(entry -> new PluginServer(plugin, entry.getKey(),
                        reader.definition(canonicalName(plugin, entry.getKey()), ExtensionScope.PLUGIN, entry.getValue(), directory, variables)))
                .filter(server -> !server.definition().command().isBlank() || !server.definition().url().isBlank()).toList();
    }

    /** Claude's plugin data folder: the id with other characters as {@code -}, so the name cannot leave plugins/data. */
    private Path dataDirectory(String id) {
        return host.claudeConfigDirectory().resolve("plugins").resolve("data").resolve(UNSAFE.matcher(id).replaceAll("-"));
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException | RuntimeException e) {
            log.debug("The plugin data folder {} could not be created", directory.getFileName());
        }
    }

    /** A servers file is either {@code {"mcpServers": {...}}} or the bare map; an inline manifest value is the bare map. */
    private static void collect(JsonNode node, Map<String, JsonNode> into) {
        var map = node.path(SERVERS).isObject() ? node.path(SERVERS) : node;
        map.properties().stream().filter(entry -> entry.getValue().isObject()).forEach(entry -> into.put(entry.getKey(), entry.getValue()));
    }

    /** Reads a file named relative to the plugin folder; an absolute name, one leaving the folder and a symlink that resolves outside it read as empty. */
    private static JsonNode readInside(Path install, String relative) {
        try {
            if (relative.startsWith("/") || relative.startsWith("\\") || Path.of(relative).isAbsolute()) {
                return Json.parse("{}");
            }
            var file = install.resolve(relative).normalize();
            var real = file.startsWith(install.normalize()) ? file.toRealPath() : null;
            return real != null && real.startsWith(install.toRealPath()) ? McpConfigReader.readLenient(real) : Json.parse("{}");
        } catch (IOException | RuntimeException e) {
            return Json.parse("{}");
        }
    }

    /** The install each plugin id uses here: the most specific scope that applies, the most recently updated among equals; orphaned or missing folders never count. */
    private Map<String, Chosen> installs(Path directory, Path projectRoot) {
        var chosen = new LinkedHashMap<String, Chosen>();
        McpConfigReader.readLenient(host.claudeConfigDirectory().resolve("plugins").resolve("installed_plugins.json")).path("plugins").properties().forEach(entry -> {
            var applicable = entry.getValue().valueStream().map(PluginMcpServers::parse).flatMap(Optional::stream).filter(install -> applies(install, directory, projectRoot)).toList();
            applicable.stream().filter(this::usable)
                    .max(Comparator.<Install>comparingInt(install -> SCOPE_PRECEDENCE.indexOf(install.scope())).thenComparing(Install::updated))
                    .ifPresent(best -> chosen.put(entry.getKey(), new Chosen(best.path(), applicable.stream().anyMatch(install -> !install.scope().equals(USER_SCOPE)))));
        });
        return chosen;
    }

    /** Every field is read inside the guard: one malformed entry (bad path characters, wrong types) drops only itself. */
    private static Optional<Install> parse(JsonNode node) {
        try {
            var path = text(node, "installPath");
            return Optional.of(new Install(path.isBlank() ? null : Path.of(path), text(node, "scope"), text(node, "projectPath"), text(node, "lastUpdated")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean applies(Install install, Path directory, Path projectRoot) {
        if (install.scope().equals(USER_SCOPE)) {
            return true;
        }
        var wanted = reader.normalise(install.projectPath());
        return SCOPE_PRECEDENCE.contains(install.scope()) && !install.projectPath().isBlank()
                && Stream.of(directory, projectRoot).filter(Objects::nonNull).anyMatch(path -> reader.normalise(path.toString()).equals(wanted));
    }

    private boolean usable(Install install) {
        return install.path() != null && host.isDirectory(install.path()) && !host.isFile(install.path().resolve(ORPHAN_MARKER));
    }

    private Settings settings(Path directory) {
        var repository = directory == null ? List.<JsonNode>of()
                : List.of(McpConfigReader.readLenient(directory.resolve(".claude/settings.json")), McpConfigReader.readLenient(directory.resolve(".claude/settings.local.json")));
        return new Settings(McpConfigReader.readLenient(host.claudeConfigDirectory().resolve("settings.json")), repository, McpConfigReader.readLenient(managedSettingsFile()));
    }

    /**
     * Managed settings decide alone; repository settings (a hostile repo writes them) can disable a plugin, but enable one only when
     * a project or local install of it names this project or the user settings enable it too.
     */
    private static boolean enabled(Settings settings, String id, boolean projectInstalled) {
        var managed = flag(settings.managed(), id);
        if (managed != null) {
            return managed;
        }
        var user = Boolean.TRUE.equals(flag(settings.user(), id));
        var repository = settings.repository().stream().map(file -> flag(file, id)).filter(Objects::nonNull).reduce((earlier, later) -> later);
        return repository.isEmpty() ? user : repository.get() && (user || projectInstalled);
    }

    private static Boolean flag(JsonNode settings, String id) {
        var value = settings.path("enabledPlugins").path(id);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    /** Where Claude Code looks for managed settings on this OS; the managed-settings.d drop-ins are not read. */
    private Path managedSettingsFile() {
        return switch (host.os()) {
            case WINDOWS -> Path.of(Optional.ofNullable(host.env("ProgramFiles")).filter(value -> !value.isBlank()).orElse("C:\\Program Files"), "ClaudeCode", "managed-settings.json");
            case MACOS -> Path.of("/Library/Application Support/ClaudeCode/managed-settings.json");
            case LINUX -> Path.of("/etc/claude-code/managed-settings.json");
        };
    }
}
