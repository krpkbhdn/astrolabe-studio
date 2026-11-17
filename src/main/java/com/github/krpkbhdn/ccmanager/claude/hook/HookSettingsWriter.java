package com.github.krpkbhdn.ccmanager.claude.hook;

import com.github.krpkbhdn.ccmanager.claude.event.HookEventType;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeVersion;
import com.github.krpkbhdn.ccmanager.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.ccmanager.claude.mcp.McpEndpoint;
import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.mcp.CodeModeEndpoint;
import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.overlay.OverlayProperties;
import com.github.krpkbhdn.ccmanager.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlay;
import com.github.krpkbhdn.ccmanager.overlay.SessionOverlay;
import com.github.krpkbhdn.ccmanager.overlay.SettingsMerge;
import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import com.github.krpkbhdn.ccmanager.team.Team;
import com.github.krpkbhdn.ccmanager.team.TeamAgentsJson;
import com.github.krpkbhdn.ccmanager.team.TeamRole;
import com.github.krpkbhdn.ccmanager.workspace.TreeDeleter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes the per-session files for claude: --settings (hooks, a statusLine that reports rate limits) and --mcp-config (cc-manager's own MCP server). */
@Component
public class HookSettingsWriter {

    private static final Logger log = LoggerFactory.getLogger(HookSettingsWriter.class);
    private static final String HOOKS_DIR = "hooks";
    private static final String FILE_SUFFIX = ".json";
    private static final String MCP_SUFFIX = ".mcp.json";
    private static final String LIST_RUN_CONFIGURATIONS_TOOL = "list_run_configurations";
    private static final String TOKEN_SUFFIX = ".token";
    private static final String PROMPT_SUFFIX = ".prompt.md";
    private static final String AGENTS_PLUGIN_SUFFIX = ".agents";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final int HOOK_TIMEOUT_SECONDS = 5;
    /** These calls are held until the user answers; the hook timeout is HookProperties.decisionHold plus this margin. */
    private static final int DECISION_TIMEOUT_MARGIN_SECONDS = 10;
    /** Claude Code's per-call limit for cc-manager-code must outlast the script limits so our own error texts name the real limit. */
    private static final Duration CODE_MODE_TIMEOUT_MARGIN = Duration.ofSeconds(30);
    /** Claude Code that knows the hook {@code args} field (changelog 2.1.139); an older one would run the command bare and start a second app. */
    private static final String EXEC_FORM_SINCE = "2.1.139";
    /** WorktreeCreate replaces git behaviour, MessageDisplay is a token firehose, the rest never fire in sessions. */
    private static final Set<HookEventType> NOT_SUBSCRIBED = EnumSet.of(HookEventType.SETUP, HookEventType.WORKTREE_CREATE,
            HookEventType.WORKTREE_REMOVE, HookEventType.MESSAGE_DISPLAY, HookEventType.UNKNOWN);

    private final HookServer server;
    private final Path hooksRoot;
    /** Every app process writes into its own subdirectory named by its pid, so instances never touch each other's files. */
    private final Path hooksDir;
    private final JsonMapper mapper;
    private final boolean windows;
    private final int decisionTimeoutSeconds;
    private final HookHelperCommand helper;
    private final ClaudeInstallationService installation;
    private final long codeModeTimeoutMillis;
    private final int maxAgentsJsonChars;
    private final AtomicBoolean skipWarned = new AtomicBoolean();

    @Autowired
    public HookSettingsWriter(HookServer server, AppProperties properties, JsonMapper mapper, HookProperties hookProperties, HookHelperCommand helper,
            ClaudeInstallationService installation, CodeModeProperties codeModeProperties, OverlayProperties overlayProperties) {
        this.maxAgentsJsonChars = overlayProperties.maxAgentsJsonChars();
        this.codeModeTimeoutMillis = codeModeProperties.maxScriptTime().plus(CODE_MODE_TIMEOUT_MARGIN).toMillis();
        this.installation = installation;
        this.helper = helper;
        this.server = server;
        this.decisionTimeoutSeconds = (int) hookProperties.decisionHold().toSeconds() + DECISION_TIMEOUT_MARGIN_SECONDS;
        this.hooksRoot = properties.dataDir().resolve(HOOKS_DIR);
        this.hooksDir = hooksRoot.resolve(String.valueOf(ProcessHandle.current().pid()));
        this.mapper = mapper;
        this.windows = helper.windows();
    }

    /** Removes the files of app processes that no longer run (a live process's directory stays however quiet its sessions are), and loose files from before the per-process directories. */
    @PostConstruct
    void deleteStaleFiles() throws IOException {
        if (!Files.isDirectory(hooksRoot)) {
            return;
        }
        try (var entries = Files.newDirectoryStream(hooksRoot)) {
            for (var entry : entries) {
                if (!Files.isDirectory(entry)) {
                    Files.deleteIfExists(entry);
                } else if (!entry.equals(hooksDir) && !isLiveProcess(entry.getFileName().toString())) {
                    TreeDeleter.delete(entry);
                }
            }
        }
    }

    private static boolean isLiveProcess(String directoryName) {
        try {
            return ProcessHandle.of(Long.parseLong(directoryName)).isPresent();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public SessionFiles write(String ccmSessionId) {
        return write(ccmSessionId, Map.of());
    }

    /** [extraServers] join the MCP config; the app's own server name stays reserved. */
    public SessionFiles write(String ccmSessionId, Map<String, JsonNode> extraServers) {
        return write(ccmSessionId, extraServers, CodeModeAccess.OFF);
    }

    /** With [codeMode] other than OFF the session also gets the Code Mode server and the listed servers are hidden from the model. */
    public SessionFiles write(String ccmSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode) {
        return write(ccmSessionId, extraServers, codeMode, TrustLevel.ASK);
    }

    /** The [trust] level adds its permission mode and allow and deny rules to the session's settings. */
    public SessionFiles write(String ccmSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode, TrustLevel trust) {
        return write(ccmSessionId, extraServers, codeMode, trust, SessionOverlay.EMPTY, null);
    }

    /**
     * The [overlay] adds its servers (except those Code Mode hides), settings, instructions and agents; [team] (may be null) only decides what the
     * instructions file holds. With an empty overlay the files are those of the overload without it.
     */
    public SessionFiles write(String ccmSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay, Team team) {
        var bearer = BEARER_PREFIX + server.sessionToken(ccmSessionId);
        var servers = new LinkedHashMap<>(extraServers);
        overlay.mcpServers().entrySet().stream().filter(entry -> !codeMode.deniedServers().contains(entry.getKey()))
                .forEach(entry -> servers.putIfAbsent(entry.getKey(), entry.getValue()));
        var config = mcpConfig(McpEndpoint.SERVER_NAME, server.mcpUrl(ccmSessionId), bearer, servers);
        if (codeMode != CodeModeAccess.OFF) {
            config.withObject("/mcpServers").putObject(CodeModeEndpoint.SERVER_NAME).put("type", "http").put("url", server.codeModeUrl(ccmSessionId))
                    .put("timeout", codeModeTimeoutMillis).putObject("headers").put("Authorization", bearer);
        }
        return write(ccmSessionId, config, codeMode, trust, overlay, team);
    }

    /**
     * The Assistant session gets only the app server in its mcp file; the project server and the project-session secret stay out of it.
     * The file holds only a reference to the token env variable of the Assistant process, so it contains no secret.
     * The token alone reaches only auto-approved tools, every other tool needs the user's Allow in the app.
     */
    public SessionFiles writeAssistant(String ccmSessionId) {
        return write(ccmSessionId, mcpConfig(AssistantMcpServer.SERVER_NAME, server.assistantMcpUrl(), BEARER_PREFIX + server.assistantMcpToken(), Map.of()),
                CodeModeAccess.OFF, TrustLevel.ASK, SessionOverlay.EMPTY, null);
    }

    /** Value of {@link HookServer#SESSION_TOKEN_ENV}: the fallback for the helper, the files hold the token themselves because a restarted claude keeps their paths but not the environment. */
    public String sessionToken(String ccmSessionId) {
        return server.sessionToken(ccmSessionId);
    }

    private SessionFiles write(String ccmSessionId, ObjectNode mcpConfig, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay, Team team) {
        var file = fileOf(ccmSessionId);
        var mcpFile = mcpFileOf(ccmSessionId);
        Path promptFile;
        Path agentsPlugin;
        try {
            OwnerOnlyFiles.createDirectory(hooksDir);
            OwnerOnlyFiles.write(tokenFileOf(ccmSessionId), server.sessionToken(ccmSessionId));
            OwnerOnlyFiles.write(file, mapper.writeValueAsString(settings(ccmSessionId, codeMode, trust, overlay)));
            OwnerOnlyFiles.write(mcpFile, mapper.writeValueAsString(mcpConfig));
            promptFile = writePrompt(ccmSessionId, overlay, team);
            agentsPlugin = writeAgentsPlugin(ccmSessionId, overlay, team);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write hook settings " + file, e);
        }
        return new SessionFiles(file, mcpFile, promptFile, agentsPlugin, overlay);
    }

    /** One file for --append-system-prompt-file: the lead addendum of the team, then the overlay instructions; claude never gets both flags. */
    private Path writePrompt(String ccmSessionId, SessionOverlay overlay, Team team) throws IOException {
        if (overlay.instructions().isBlank()) {
            return null;
        }
        var file = hooksDir.resolve(ccmSessionId + PROMPT_SUFFIX).toAbsolutePath();
        OwnerOnlyFiles.write(file, (team == null ? "" : TeamAgentsJson.leadAddendum(team) + "\n\n") + overlay.instructions());
        return file;
    }

    /** Agents too long for the command line travel as a plugin directory; claude then names them {@code ccm-overlay-agents:<name>} instead of {@code <name>}. */
    private Path writeAgentsPlugin(String ccmSessionId, SessionOverlay overlay, Team team) throws IOException {
        if (overlay.agents().isEmpty() || commandLineLength(TeamAgentsJson.agents(team, overlay.agents().values())) <= maxAgentsJsonChars) {
            return null;
        }
        log.warn("The overlay agents do not fit into --agents, they are passed as a plugin directory");
        var teamNames = team == null ? Set.<String>of() : team.subagents().stream().map(TeamRole::name).collect(Collectors.toSet());
        var directory = hooksDir.resolve(ccmSessionId + AGENTS_PLUGIN_SUFFIX).toAbsolutePath();
        OwnerOnlyFiles.createDirectory(directory.resolve(".claude-plugin"));
        OwnerOnlyFiles.createDirectory(directory.resolve("agents"));
        OwnerOnlyFiles.write(directory.resolve(".claude-plugin").resolve("plugin.json"),
                mapper.writeValueAsString(mapper.createObjectNode().put("name", "ccm-overlay-agents").put("description", "Agents of the project overlay")));
        for (var agent : overlay.agents().values()) {
            if (!teamNames.contains(agent.name())) {
                OwnerOnlyFiles.write(directory.resolve("agents").resolve(agent.name() + ".md"), agent.markdown());
            }
        }
        return directory;
    }

    /** Windows quoting puts a backslash before every quote and may double backslashes, so each of them counts twice. */
    private static int commandLineLength(String argument) {
        return argument.length() + (int) argument.chars().filter(c -> c == '"' || c == '\\').count();
    }

    public void delete(String ccmSessionId) {
        try {
            Files.deleteIfExists(fileOf(ccmSessionId));
            Files.deleteIfExists(mcpFileOf(ccmSessionId));
            Files.deleteIfExists(tokenFileOf(ccmSessionId));
            Files.deleteIfExists(hooksDir.resolve(ccmSessionId + PROMPT_SUFFIX));
            var agentsPlugin = hooksDir.resolve(ccmSessionId + AGENTS_PLUGIN_SUFFIX);
            if (Files.exists(agentsPlugin)) {
                TreeDeleter.delete(agentsPlugin);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete hook settings of " + ccmSessionId, e);
        }
    }

    ObjectNode settings(String ccmSessionId) {
        return settings(ccmSessionId, CodeModeAccess.OFF);
    }

    ObjectNode settings(String ccmSessionId, CodeModeAccess codeMode) {
        return settings(ccmSessionId, codeMode, TrustLevel.ASK);
    }

    /**
     * Only enabledPlugins and the deny and ask rules of the overlay settings are merged under the app's (an allowlist: defaultMode, allow rules, env,
     * helpers and the like never reach a launch); every overlay folder becomes edit-protected and its mcp.json and settings.json unreadable.
     * shortcut: a Bash redirect or cat is not covered by these rules, only Claude's own permission dialog guards it.
     */
    ObjectNode settings(String ccmSessionId, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay) {
        var app = settings(ccmSessionId, codeMode, trust);
        if (overlay.isEmpty()) {
            return app;
        }
        var deny = app.withArray("/permissions/deny");
        overlay.roots().forEach(root -> {
            var protectedRoot = new ProjectOverlay(root);
            deny.add("Edit(" + absoluteRule(root) + "/**)");
            deny.add("Read(" + absoluteRule(protectedRoot.mcpFile()) + ")");
            deny.add("Read(" + absoluteRule(protectedRoot.settingsFile()) + ")");
        });
        return SettingsMerge.deep(app, launchAllowed(overlay.settings()));
    }

    private ObjectNode launchAllowed(ObjectNode overlaySettings) {
        var allowed = mapper.createObjectNode();
        if (overlaySettings.path("enabledPlugins") instanceof ObjectNode plugins) {
            allowed.set("enabledPlugins", plugins.deepCopy());
        }
        for (var rules : new String[] {"deny", "ask"}) {
            if (overlaySettings.path("permissions").path(rules) instanceof ArrayNode list) {
                allowed.withObject("/permissions").set(rules, list.deepCopy());
            }
        }
        return allowed;
    }

    ObjectNode settings(String ccmSessionId, CodeModeAccess codeMode, TrustLevel trust) {
        var hooks = mapper.createObjectNode();
        Arrays.stream(HookEventType.values())
                .filter(type -> !NOT_SUBSCRIBED.contains(type))
                .filter(type -> type != HookEventType.SESSION_START || execFormSupported(ccmSessionId))
                .forEach(type -> hooks.putArray(type.wireName()).addObject().putArray("hooks").add(handler(type, ccmSessionId)));
        var settings = mapper.createObjectNode();
        settings.set("hooks", hooks);
        helper.shellCommand(server.statusLineUrl(ccmSessionId), tokenFileOf(ccmSessionId)).ifPresent(command -> settings.set("statusLine", statusLine(command)));
        var permissions = settings.putObject("permissions");
        var allow = permissions.putArray("allow").add("mcp__" + McpEndpoint.SERVER_NAME + "__" + LIST_RUN_CONFIGURATIONS_TOOL);
        TrustPermissions.allow(trust).forEach(allow::add);
        var deny = permissions.putArray("deny");
        deny.add("Read(" + absoluteRule(hooksRoot) + "/**)");
        TrustPermissions.deny(trust).forEach(deny::add);
        TrustPermissions.defaultMode(trust).ifPresent(mode -> permissions.put("defaultMode", mode));
        if (codeMode != CodeModeAccess.OFF) {
            applyCodeMode(settings, permissions, deny, codeMode);
        }
        return settings;
    }

    /** Only the read-only tools are pre-approved; execute keeps Claude's own permission dialog. */
    private static void applyCodeMode(ObjectNode settings, ObjectNode permissions, ArrayNode deny, CodeModeAccess codeMode) {
        var allow = (ArrayNode) permissions.get("allow");
        allow.add("mcp__" + CodeModeEndpoint.SERVER_NAME + "__search_tools").add("mcp__" + CodeModeEndpoint.SERVER_NAME + "__list_servers");
        codeMode.deniedServers().forEach(name -> deny.add("mcp__" + name));
        if (!codeMode.hiddenProjectServers().isEmpty()) {
            var hidden = settings.putArray("disabledMcpjsonServers");
            codeMode.hiddenProjectServers().forEach(hidden::add);
        }
    }

    private boolean execFormSupported(String ccmSessionId) {
        if (helper.execForm(server.hookUrl(ccmSessionId), tokenFileOf(ccmSessionId)).isEmpty()) {
            return false;
        }
        var version = installation.current().version();
        if (version == null) {
            version = installation.recheck().version();
        }
        var supported = ClaudeVersion.atLeast(version, EXEC_FORM_SINCE);
        if (!supported && !skipWarned.getAndSet(true)) {
            log.warn("The SessionStart hook is omitted: Claude Code version {} is unknown or older than {} (hook args field)", version, EXEC_FORM_SINCE);
        }
        return supported;
    }

    /** Claude Code rules take an absolute path as {@code //path}; Windows drives are written Git Bash style ({@code //c/Users/...}). shortcut: Bash cat is not covered by Read rules. */
    private String absoluteRule(Path path) {
        var text = path.toAbsolutePath().toString().replace('\\', '/');
        if (windows && text.length() > 1 && text.charAt(1) == ':') {
            return "//" + Character.toLowerCase(text.charAt(0)) + text.substring(2);
        }
        return "/" + text;
    }

    /** Only the read-only list tool is pre-approved; set and delete change a command the user later runs, so Claude asks first. */
    private ObjectNode mcpConfig(String serverName, String url, String authorization, Map<String, JsonNode> extraServers) {
        var config = mapper.createObjectNode();
        var servers = config.putObject("mcpServers");
        var own = servers.putObject(serverName).put("type", "http").put("url", url);
        own.putObject("headers").put("Authorization", authorization);
        extraServers.forEach((name, definition) -> servers.putIfAbsent(name, definition.deepCopy()));
        return config;
    }

    private ObjectNode handler(HookEventType type, String ccmSessionId) {
        if (type == HookEventType.SESSION_START) {
            return commandHandler(ccmSessionId);
        }
        return httpHandler(ccmSessionId, type.awaitsDecision() ? decisionTimeoutSeconds : HOOK_TIMEOUT_SECONDS);
    }

    /** The token is written literally: after claude restarts its session (agent view) the environment is foreign but the settings file stays. */
    private ObjectNode httpHandler(String ccmSessionId, int timeoutSeconds) {
        var handler = mapper.createObjectNode().put("type", "http").put("url", server.hookUrl(ccmSessionId)).put("timeout", timeoutSeconds);
        handler.putObject("headers").put("Authorization", BEARER_PREFIX + server.sessionToken(ccmSessionId));
        return handler;
    }

    /** SessionStart does not accept http handlers; exec form (no shell, nothing to quote) and async so a stalled helper can never delay startup. */
    private ObjectNode commandHandler(String ccmSessionId) {
        var exec = helper.execForm(server.hookUrl(ccmSessionId), tokenFileOf(ccmSessionId)).orElseThrow();
        var handler = mapper.createObjectNode().put("type", "command").put("command", exec.command());
        exec.args().forEach(handler.putArray("args")::add);
        return handler.put("async", true).put("timeout", HOOK_TIMEOUT_SECONDS);
    }

    /**
     * The statusLine payload is the only carrier of rate_limits. The command is one shell string (statusLine has no args form)
     * and prints nothing, so the status line stays empty. shortcut: a user's own statusLine is replaced in our sessions, chain it when asked.
     */
    private ObjectNode statusLine(String command) {
        return mapper.createObjectNode().put("type", "command").put("command", command);
    }

    private Path tokenFileOf(String ccmSessionId) {
        return hooksDir.resolve(ccmSessionId + TOKEN_SUFFIX).toAbsolutePath();
    }

    private Path mcpFileOf(String ccmSessionId) {
        return hooksDir.resolve(ccmSessionId + MCP_SUFFIX);
    }

    private Path fileOf(String ccmSessionId) {
        if (!SAFE_ID.matcher(ccmSessionId).matches()) {
            throw new IllegalArgumentException("Invalid session id");
        }
        return hooksDir.resolve(ccmSessionId + FILE_SUFFIX);
    }
}
