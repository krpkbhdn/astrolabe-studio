package com.github.krpkbhdn.ccmanager.claude.hook;

import com.github.krpkbhdn.ccmanager.claude.event.HookEventType;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeVersion;
import com.github.krpkbhdn.ccmanager.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.ccmanager.claude.mcp.McpEndpoint;
import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.mcp.CodeModeEndpoint;
import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.workspace.TreeDeleter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
    private final AtomicBoolean skipWarned = new AtomicBoolean();

    @Autowired
    public HookSettingsWriter(HookServer server, AppProperties properties, JsonMapper mapper, HookProperties hookProperties, HookHelperCommand helper,
            ClaudeInstallationService installation, CodeModeProperties codeModeProperties) {
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
        var bearer = BEARER_PREFIX + server.sessionToken(ccmSessionId);
        var config = mcpConfig(McpEndpoint.SERVER_NAME, server.mcpUrl(ccmSessionId), bearer, extraServers);
        if (codeMode != CodeModeAccess.OFF) {
            config.withObject("/mcpServers").putObject(CodeModeEndpoint.SERVER_NAME).put("type", "http").put("url", server.codeModeUrl(ccmSessionId))
                    .put("timeout", codeModeTimeoutMillis).putObject("headers").put("Authorization", bearer);
        }
        return write(ccmSessionId, config, codeMode);
    }

    /**
     * The Assistant session gets only the app server in its mcp file; the project server and the project-session secret stay out of it.
     * The file holds only a reference to the token env variable of the Assistant process, so it contains no secret.
     * The token alone reaches only auto-approved tools, every other tool needs the user's Allow in the app.
     */
    public SessionFiles writeAssistant(String ccmSessionId) {
        return write(ccmSessionId, mcpConfig(AssistantMcpServer.SERVER_NAME, server.assistantMcpUrl(), BEARER_PREFIX + server.assistantMcpToken(), Map.of()),
                CodeModeAccess.OFF);
    }

    /** Value of {@link HookServer#SESSION_TOKEN_ENV}: the fallback for the helper, the files hold the token themselves because a restarted claude keeps their paths but not the environment. */
    public String sessionToken(String ccmSessionId) {
        return server.sessionToken(ccmSessionId);
    }

    private SessionFiles write(String ccmSessionId, ObjectNode mcpConfig, CodeModeAccess codeMode) {
        var file = fileOf(ccmSessionId);
        var mcpFile = mcpFileOf(ccmSessionId);
        try {
            restrictToOwner(Files.createDirectories(hooksDir), "rwx------");
            writeOwnerOnly(tokenFileOf(ccmSessionId), server.sessionToken(ccmSessionId));
            writeOwnerOnly(file, mapper.writeValueAsString(settings(ccmSessionId, codeMode)));
            writeOwnerOnly(mcpFile, mapper.writeValueAsString(mcpConfig));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write hook settings " + file, e);
        }
        return new SessionFiles(file, mcpFile);
    }

    /** The files hold the session's token; the group and others must not read them. Created with the permissions where POSIX exists, else the ACL is cut to the owner before any content is written. */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        var posix = file.getFileSystem().supportedFileAttributeViews().contains("posix");
        var temp = posix
                ? Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            if (!posix) {
                restrictAclToOwner(temp);
            }
            Files.writeString(temp, content);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void restrictToOwner(Path path, String permissions) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }

    /** Replaces the inherited ACL (and every other entry) with one full-control entry for the file's owner. */
    private static void restrictAclToOwner(Path file) throws IOException {
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view != null) {
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }

    public void delete(String ccmSessionId) {
        try {
            Files.deleteIfExists(fileOf(ccmSessionId));
            Files.deleteIfExists(mcpFileOf(ccmSessionId));
            Files.deleteIfExists(tokenFileOf(ccmSessionId));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete hook settings of " + ccmSessionId, e);
        }
    }

    ObjectNode settings(String ccmSessionId) {
        return settings(ccmSessionId, CodeModeAccess.OFF);
    }

    ObjectNode settings(String ccmSessionId, CodeModeAccess codeMode) {
        var hooks = mapper.createObjectNode();
        Arrays.stream(HookEventType.values())
                .filter(type -> !NOT_SUBSCRIBED.contains(type))
                .filter(type -> type != HookEventType.SESSION_START || execFormSupported(ccmSessionId))
                .forEach(type -> hooks.putArray(type.wireName()).addObject().putArray("hooks").add(handler(type, ccmSessionId)));
        var settings = mapper.createObjectNode();
        settings.set("hooks", hooks);
        helper.shellCommand(server.statusLineUrl(ccmSessionId), tokenFileOf(ccmSessionId)).ifPresent(command -> settings.set("statusLine", statusLine(command)));
        var permissions = settings.putObject("permissions");
        permissions.putArray("allow")
                .add("mcp__" + McpEndpoint.SERVER_NAME + "__" + LIST_RUN_CONFIGURATIONS_TOOL);
        var deny = permissions.putArray("deny");
        deny.add("Read(" + absoluteRule(hooksRoot) + "/**)");
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
