package com.github.krpkbhdn.astrolabe.extension.mcp;

import com.github.krpkbhdn.astrolabe.extension.ClaudeCli;
import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.ExtensionProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.Json;
import com.github.krpkbhdn.astrolabe.extension.ProjectOverlays;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP servers: configuration is read from Claude's files, changes go through {@code claude mcp}. Project-level servers of a registered
 * project (scope PROJECT or APP) are kept in the project's overlay instead, never written into the project folder.
 * Server configs hold secrets, so nothing here logs them. Blocking; throws {@link ExtensionException}.
 */
@Service
public class McpService {

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.: -]*");

    private final ClaudeCli cli;
    private final McpConfigReader configReader;
    private final McpCredentialReader credentials;
    private final ExtensionProperties properties;
    private final ProjectOverlays overlays;

    McpService(ClaudeCli cli, McpConfigReader configReader, McpCredentialReader credentials, ExtensionProperties properties, ProjectOverlays overlays) {
        this.overlays = overlays;
        this.cli = cli;
        this.configReader = configReader;
        this.credentials = credentials;
        this.properties = properties;
    }

    /** Servers declared in the user config and, with a project, in its local and shared config (no plugin servers). */
    public List<McpServerConfig> servers(Path projectDirectory) {
        return configReader.read(projectDirectory);
    }

    /** Slow: claude health-checks every server. Includes plugin servers, which have no config of their own. */
    public List<McpHealth> health(Path projectDirectory) {
        return McpListParser.parse(cli.executeChecked(projectDirectory, properties.mcpHealthTimeout(), List.of("mcp", "list")));
    }

    /** Raw {@code claude mcp get} text; may contain secrets, show it but do not log it. */
    public String details(String name, Path projectDirectory) {
        return cli.executeChecked(projectDirectory, properties.mcpHealthTimeout(), List.of("mcp", "get", requireValidName(name)));
    }

    /** @param json the server config object, e.g. {@code {"type":"http","url":"..."}} */
    public void add(String name, String json, ExtensionScope scope, Path projectDirectory) {
        requireValidName(name);
        if (name.contains(": ") || name.contains(" - ")) {
            throw new ExtensionException("Invalid server name: \": \" and \" - \" would break the server list");
        }
        if (!Json.parse(json).isObject()) {
            throw new ExtensionException("The server config must be a JSON object");
        }
        var overlay = overlayFor(scope, projectDirectory);
        if (overlay.isPresent()) {
            addToOverlay(overlay.get(), projectDirectory, name, json);
            return;
        }
        try {
            cli.executeChecked(scope.workingDirectory(projectDirectory), properties.queryTimeout(),
                    List.of("mcp", "add-json", "--scope", scope.cliValue(), name, json));
        } catch (ExtensionException e) {
            throw new ExtensionException(without(json, e.getMessage()), without(json, e.output()));
        }
    }

    /** The CLI may echo the config, its url or arguments, or single env and header values in an error; keep them out of messages. */
    private static String without(String json, String text) {
        var redacted = text.replace(json, "<config>");
        var root = Json.parse(json);
        var url = root.path("url").asString("");
        if (!url.isBlank()) {
            redacted = redacted.replace(url, McpTargetRedaction.redact(url));
        }
        var commandLine = commandLine(root);
        if (commandLine.contains(" ")) {
            redacted = redacted.replace(commandLine, McpTargetRedaction.redact(commandLine));
        }
        for (var secret : secretValues(json)) {
            redacted = redacted.replace(secret, "<secret>");
        }
        return redacted;
    }

    private static String commandLine(JsonNode root) {
        var parts = new ArrayList<String>();
        parts.add(root.path("command").asString(""));
        root.path("args").forEach(arg -> parts.add(arg.asString("")));
        return String.join(" ", parts).strip();
    }

    private static List<String> secretValues(String json) {
        var values = new ArrayList<String>();
        var root = Json.parse(json);
        for (var field : List.of("env", "headers")) {
            root.path(field).forEach(value -> values.add(value.asString("")));
        }
        return values.stream().filter(value -> !value.isBlank()).sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** Names of servers with a stored OAuth token; empty when unknown. Never exposes the tokens. */
    public Set<String> loggedInServers() {
        return credentials.loggedInServers();
    }

    /**
     * Browser OAuth sign-in in a pseudo terminal (claude refuses without one): blocks until the redirect arrives or the
     * login timeout passes. Interrupting the thread kills the process, which is how a caller cancels.
     * @param onSignInUrl called once with the sign-in page claude prints for when the browser did not open
     */
    public void login(String name, Path projectDirectory, Consumer<String> onSignInUrl) {
        var arguments = List.of("mcp", "login", requireValidName(name));
        var urlReported = new AtomicBoolean();
        var result = cli.executeInTerminal(projectDirectory, properties.mcpLoginTimeout(), arguments, output ->
                McpLoginOutput.signInUrl(output).filter(url -> urlReported.compareAndSet(false, true)).ifPresent(onSignInUrl));
        if (!result.succeeded()) {
            var text = McpLoginOutput.failureText(result.stdout() + "\n" + result.stderr());
            throw new ExtensionException("claude mcp login failed: " + (text.isBlank() ? "exit code " + result.exitCode() : text), text);
        }
    }

    public void logout(String name, Path projectDirectory) {
        cli.executeChecked(projectDirectory, properties.queryTimeout(), List.of("mcp", "logout", requireValidName(name)));
    }

    public void remove(String name, ExtensionScope scope, Path projectDirectory) {
        var overlay = overlayFor(scope, projectDirectory);
        if (overlay.isPresent() && (scope == ExtensionScope.APP || overlayServers(overlay.get()).has(name))) {
            removeFromOverlay(overlay.get(), requireValidName(name));
            return;
        }
        cli.executeChecked(scope.workingDirectory(projectDirectory), properties.queryTimeout(),
                List.of("mcp", "remove", "--scope", scope.cliValue(), requireValidName(name)));
    }

    /** The overlay a project-level change goes to: that of the registered project at the directory; none (the CLI writes the folder) for an unregistered one unless the scope is APP. */
    private Optional<ProjectOverlay> overlayFor(ExtensionScope scope, Path projectDirectory) {
        if (scope != ExtensionScope.PROJECT && scope != ExtensionScope.APP) {
            return Optional.empty();
        }
        var overlay = overlays.of(projectDirectory);
        if (overlay.isEmpty() && scope == ExtensionScope.APP) {
            throw new ExtensionException("app scope needs a registered project");
        }
        return overlay;
    }

    /** A name that Claude already resolves to an in-project .mcp.json or user-scope server would be shadowed or shadow it, so it is refused. */
    private void addToOverlay(ProjectOverlay overlay, Path projectDirectory, String name, String json) {
        var root = overlayConfig(overlay);
        var servers = root.withObject("/mcpServers");
        if (servers.has(name) || configReader.read(projectDirectory).stream()
                .anyMatch(server -> server.name().equals(name) && (server.scope() == ExtensionScope.PROJECT || server.scope() == ExtensionScope.USER))) {
            throw new ExtensionException("An MCP server named " + name + " already exists for this project");
        }
        servers.set(name, Json.parse(json));
        writeOverlay(overlay, root);
    }

    private void removeFromOverlay(ProjectOverlay overlay, String name) {
        var root = overlayConfig(overlay);
        if (root.withObject("/mcpServers").remove(name) == null) {
            throw new ExtensionException("No MCP server named " + name + " in this project");
        }
        writeOverlay(overlay, root);
    }

    private static JsonNode overlayServers(ProjectOverlay overlay) {
        return overlayConfig(overlay).path("mcpServers");
    }

    /** A file that is not a JSON object is refused, never overwritten. */
    private static ObjectNode overlayConfig(ProjectOverlay overlay) {
        try {
            return overlay.readObject(overlay.mcpFile());
        } catch (IOException e) {
            throw new ExtensionException("Cannot read the project's MCP servers: " + e.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            throw new ExtensionException("The project's MCP server file is not a JSON object, fix or delete mcp.json in its app folder");
        }
    }

    private static void writeOverlay(ProjectOverlay overlay, ObjectNode root) {
        try {
            overlay.write(overlay.mcpFile(), root.toPrettyString());
        } catch (IOException e) {
            throw new ExtensionException("Cannot write the project's MCP servers: " + e.getMessage());
        }
    }

    private static String requireValidName(String name) {
        if (!SERVER_NAME.matcher(name).matches()) {
            throw new ExtensionException("Invalid server name: use letters, digits, spaces and '-', '_', '.', ':'");
        }
        return name;
    }
}
