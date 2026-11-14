package com.github.krpkbhdn.ccmanager.extension.mcp;

import static com.github.krpkbhdn.ccmanager.extension.Json.text;

import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.Json;
import com.github.krpkbhdn.ccmanager.git.GitRunner;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the MCP server declarations from ~/.claude.json (user and local scope) and the project's .mcp.json.
 * {@link #read} keeps only names, transport and target (env and headers hold secrets); {@link #definitions}, the only
 * public method that expands and returns secrets, keeps everything a client needs and must never be logged. Never writes.
 */
@Component
public class McpConfigReader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigReader.class);
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final String SERVERS = "mcpServers";
    private static final String STDIO = "stdio";
    private static final String USER_CONFIG_FILE = ".claude.json";
    private static final String PROJECT_CONFIG_FILE = ".mcp.json";
    private static final String PROJECTS = "projects";
    private static final String TRUST_ACCEPTED = "hasTrustDialogAccepted";
    private static final String USER_SETTINGS_FILE = "settings.json";
    private static final String SETTINGS_FILE = ".claude/settings.json";
    private static final String SETTINGS_LOCAL_FILE = ".claude/settings.local.json";

    private final HostEnvironment host;

    private final GitRunner git;

    McpConfigReader(HostEnvironment host, GitRunner git) {
        this.host = host;
        this.git = git;
    }

    /** Claude keeps .claude.json in CLAUDE_CONFIG_DIR when that is set, else directly in the home directory. */
    private Path userConfigFile() {
        var configured = host.env("CLAUDE_CONFIG_DIR");
        var directory = configured == null || configured.isBlank() ? host.home() : host.claudeConfigDirectory();
        return directory.resolve(USER_CONFIG_FILE);
    }

    /** User servers, plus local and project ones when a project directory is given. */
    public List<McpServerConfig> read(Path projectDirectory) {
        var userConfig = readUserConfig();
        var servers = new ArrayList<>(servers(userConfig, ExtensionScope.USER, McpApproval.NOT_REQUIRED));
        if (projectDirectory != null) {
            var projectEntry = projectEntry(userConfig, projectDirectory);
            servers.addAll(servers(projectEntry, ExtensionScope.LOCAL, McpApproval.NOT_REQUIRED));
            var approvals = List.of(readLenient(projectDirectory.resolve(SETTINGS_LOCAL_FILE)), readLenient(projectDirectory.resolve(SETTINGS_FILE)), projectEntry,
                    readLenient(host.claudeConfigDirectory().resolve(USER_SETTINGS_FILE)));
            servers.addAll(servers(readFile(projectDirectory.resolve(PROJECT_CONFIG_FILE)), ExtensionScope.PROJECT, approvals));
        }
        return List.copyOf(servers);
    }

    /**
     * The servers of {@link #read} with command, args, env, url and headers; {@code ${VAR}} and {@code ${VAR:-default}} are expanded from the process environment.
     * Stdio servers of every scope, user scope included, start in the given directory as in a Claude Code session there; without a directory they get none.
     */
    public List<McpServerDefinition> definitions(Path projectDirectory) {
        var userConfig = readUserConfig();
        var found = new ArrayList<>(definitions(userConfig, ExtensionScope.USER, projectDirectory));
        if (projectDirectory != null) {
            found.addAll(definitions(projectEntry(userConfig, projectDirectory), ExtensionScope.LOCAL, projectDirectory));
            found.addAll(definitions(readFile(projectDirectory.resolve(PROJECT_CONFIG_FILE)), ExtensionScope.PROJECT, projectDirectory));
        }
        return List.copyOf(found);
    }

    private List<McpServerDefinition> definitions(JsonNode owner, ExtensionScope scope, Path workingDirectory) {
        return owner.path(SERVERS).properties().stream().map(entry -> definition(entry.getKey(), scope, entry.getValue(), workingDirectory)).toList();
    }

    private McpServerDefinition definition(String name, ExtensionScope scope, JsonNode node, Path workingDirectory) {
        return definition(name, scope, node, workingDirectory, Map.of());
    }

    /** @param variables values for {@code ${NAME}} that win over the process environment (plugin path variables) */
    McpServerDefinition definition(String name, ExtensionScope scope, JsonNode node, Path workingDirectory, Map<String, String> variables) {
        var command = expand(text(node, "command"), variables);
        var transport = command.isEmpty() ? McpServerDefinition.Transport.HTTP : McpServerDefinition.Transport.STDIO;
        var args = node.path("args").valueStream().map(arg -> expand(arg.asString(""), variables)).toList();
        return new McpServerDefinition(name, scope, transport, command, args, expandedMap(node.path("env"), variables), expand(text(node, "url"), variables),
                expandedMap(node.path("headers"), variables), workingDirectory);
    }

    private Map<String, String> expandedMap(JsonNode object, Map<String, String> variables) {
        var expanded = new LinkedHashMap<String, String>();
        object.properties().forEach(entry -> expanded.put(entry.getKey(), expand(entry.getValue().asString(""), variables)));
        return expanded;
    }

    /** An unset variable without a default stays as written, so the failure shows up at the server, not as a silently empty secret. */
    private String expand(String value, Map<String, String> variables) {
        return VARIABLE.matcher(value).replaceAll(match -> {
            var set = variables.containsKey(match.group(1)) ? variables.get(match.group(1)) : host.env(match.group(1));
            var replacement = set != null ? set : match.group(2) != null ? match.group(2) : match.group();
            return Matcher.quoteReplacement(replacement);
        });
    }

    /**
     * Names of the directory's .mcp.json servers that the user approved outside the repository. Needs the accepted trust dialog
     * in ~/.claude.json and an approval in the directory's untracked .claude/settings.local.json; a disabled entry in
     * that file or the user settings wins, but user-level approvals are name-only and never count. A settings.local.json committed to git is ignored, as a hostile repo could approve itself.
     */
    Set<String> approvedOutsideRepository(Path projectDirectory) {
        if (!projectEntry(readUserConfig(), projectDirectory).path(TRUST_ACCEPTED).asBoolean(false)) {
            return Set.of();
        }
        var local = projectDirectory.resolve(SETTINGS_LOCAL_FILE);
        var sources = List.of(isTracked(projectDirectory, local) ? Json.parse("{}") : readFile(local),
                disabledOnly(readFile(host.claudeConfigDirectory().resolve(USER_SETTINGS_FILE))));
        return servers(readFile(projectDirectory.resolve(PROJECT_CONFIG_FILE)), ExtensionScope.PROJECT, sources).stream()
                .filter(server -> server.approval() == McpApproval.APPROVED).map(McpServerConfig::name).collect(Collectors.toSet());
    }

    /** User settings name servers without a project, so a name approved for one repository must not approve another's; only a veto carries over. */
    private static JsonNode disabledOnly(JsonNode userSettings) {
        var copy = userSettings.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove(List.of("enabledMcpjsonServers", "enableAllProjectMcpServers"));
        }
        return copy;
    }

    /** Without a .git the file cannot be committed; with one, only git's own "not tracked" answer (exit 1) clears it, any other outcome counts as tracked. */
    private boolean isTracked(Path projectDirectory, Path file) {
        if (!Files.exists(file) || !Files.exists(projectDirectory.resolve(".git"))) {
            return false;
        }
        try {
            return git.tryRun(projectDirectory, "ls-files", "--error-unmatch", "--", SETTINGS_LOCAL_FILE).exitCode() != 1;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private JsonNode projectEntry(JsonNode userConfig, Path projectDirectory) {
        var wanted = normalise(projectDirectory.toString());
        return userConfig.path(PROJECTS).properties().stream()
                .filter(entry -> normalise(entry.getKey()).equals(wanted))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Json.parse("{}"));
    }

    String normalise(String path) {
        var slashed = path.replace('\\', '/').replaceAll("/+$", "");
        return host.windows() ? slashed.toLowerCase(Locale.ROOT) : slashed;
    }

    private static List<McpServerConfig> servers(JsonNode owner, ExtensionScope scope, McpApproval approval) {
        return servers(owner, scope, name -> approval);
    }

    private static List<McpServerConfig> servers(JsonNode owner, ExtensionScope scope, List<JsonNode> approvals) {
        return servers(owner, scope, name -> approval(approvals, name));
    }

    private static List<McpServerConfig> servers(JsonNode owner, ExtensionScope scope, Function<String, McpApproval> approval) {
        return owner.path(SERVERS).properties().stream().map(entry -> {
            var command = text(entry.getValue(), "command");
            var type = text(entry.getValue(), "type");
            return new McpServerConfig(entry.getKey(), scope, type.isEmpty() ? STDIO : type,
                    McpTargetRedaction.redact(command.isEmpty() ? text(entry.getValue(), "url") : command), approval.apply(entry.getKey()));
        }).toList();
    }

    /** @param sources approval holders, most specific first: local settings, shared settings, the ~/.claude.json project entry, user settings; disabled wins */
    // shortcut: managed (enterprise) settings are not read (OS-specific paths), add them when users report it
    private static McpApproval approval(List<JsonNode> sources, String name) {
        if (sources.stream().anyMatch(source -> names(source.path("disabledMcpjsonServers")).contains(name))) {
            return McpApproval.DISABLED;
        }
        var enabled = sources.stream().anyMatch(source -> names(source.path("enabledMcpjsonServers")).contains(name));
        var enableAll = sources.stream().map(source -> source.path("enableAllProjectMcpServers")).filter(JsonNode::isBoolean)
                .findFirst().map(JsonNode::asBoolean).orElse(false);
        return enabled || enableAll ? McpApproval.APPROVED : McpApproval.PENDING;
    }

    private static Set<String> names(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.asString("")).collect(Collectors.toSet());
    }

    /** For optional files (approval sources, plugin manifests): a missing or broken one reads as empty instead of hiding every server. */
    static JsonNode readLenient(Path file) {
        try {
            return readFile(file);
        } catch (RuntimeException e) {
            return Json.parse("{}");
        }
    }

    /** A running claude rewrites ~/.claude.json, so a half-written file is treated as empty instead of failing the call. */
    private JsonNode readUserConfig() {
        var file = userConfigFile();
        try {
            return readFile(file);
        } catch (ExtensionException e) {
            log.warn("{} is unreadable, treated as empty", file.getFileName());
            return Json.parse("{}");
        }
    }

    private static JsonNode readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Json.parse("{}");
        }
        try {
            return Json.parse(Files.readString(file));
        } catch (IOException e) {
            throw new ExtensionException("Cannot read " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
