package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.astrolabe.claude.mcp.McpEndpoint;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.mcp.CodeModeEndpoint;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.overlay.OverlayProperties;
import com.github.krpkbhdn.astrolabe.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import com.github.krpkbhdn.astrolabe.team.Team;
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Writes the per-session files for claude: --settings (hooks, a statusLine that reports rate limits) and --mcp-config (astrolabe's own MCP server). */
@Component
public class HookSettingsWriter {

    private static final Logger log = LoggerFactory.getLogger(HookSettingsWriter.class);
    private static final String HOOKS_DIR = "hooks";
    private static final String BEARER_PREFIX = "Bearer ";
    /** These calls are held until the user answers; the hook timeout is HookProperties.decisionHold plus this margin. */
    private static final int DECISION_TIMEOUT_MARGIN_SECONDS = 10;
    /** Claude Code's per-call limit for astrolabe-code must outlast the script limits so our own error texts name the real limit. */
    private static final Duration CODE_MODE_TIMEOUT_MARGIN = Duration.ofSeconds(30);

    private final HookServer server;
    private final SessionPaths paths;
    private final JsonMapper mapper;
    private final SessionSettingsBuilder settingsBuilder;
    private final OverlaySessionFiles overlayFiles;
    private final long codeModeTimeoutMillis;

    @Autowired
    public HookSettingsWriter(HookServer server, AppProperties properties, JsonMapper mapper, HookProperties hookProperties, HookHelperCommand helper,
            ClaudeInstallationService installation, CodeModeProperties codeModeProperties, OverlayProperties overlayProperties) {
        this.codeModeTimeoutMillis = codeModeProperties.maxScriptTime().plus(CODE_MODE_TIMEOUT_MARGIN).toMillis();
        this.server = server;
        var hooksRoot = properties.dataDir().resolve(HOOKS_DIR);
        this.paths = new SessionPaths(hooksRoot, hooksRoot.resolve(String.valueOf(ProcessHandle.current().pid())));
        this.mapper = mapper;
        this.settingsBuilder = new SessionSettingsBuilder(server, mapper, helper, installation, paths,
                (int) hookProperties.decisionHold().toSeconds() + DECISION_TIMEOUT_MARGIN_SECONDS);
        this.overlayFiles = new OverlaySessionFiles(mapper, paths, overlayProperties.maxAgentsJsonChars());
    }

    /** Removes the files of app processes that no longer run (a live process's directory stays however quiet its sessions are), and loose files from before the per-process directories. */
    @PostConstruct
    void deleteStaleFiles() {
        if (!Files.isDirectory(paths.hooksRoot())) {
            return;
        }
        try (var entries = Files.newDirectoryStream(paths.hooksRoot())) {
            for (var entry : entries) {
                deleteIfStale(entry);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not list stale hook files: {}", e.getClass().getSimpleName());
        }
    }

    private void deleteIfStale(Path entry) {
        try {
            if (!Files.isDirectory(entry)) {
                Files.deleteIfExists(entry);
            } else if (!entry.equals(paths.hooksDir()) && !isLiveProcess(entry.getFileName().toString())) {
                TreeDeleter.delete(entry);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stale hook entry {}: {}", entry.getFileName(), e.getClass().getSimpleName());
        }
    }

    private static boolean isLiveProcess(String directoryName) {
        try {
            return ProcessHandle.of(Long.parseLong(directoryName)).isPresent();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public SessionFiles write(String astrolabeSessionId) {
        return write(astrolabeSessionId, Map.of());
    }

    /** [extraServers] join the MCP config; the app's own server name stays reserved. */
    public SessionFiles write(String astrolabeSessionId, Map<String, JsonNode> extraServers) {
        return write(astrolabeSessionId, extraServers, CodeModeAccess.OFF);
    }

    /** With [codeMode] other than OFF the session also gets the Code Mode server and the listed servers are hidden from the model. */
    public SessionFiles write(String astrolabeSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode) {
        return write(astrolabeSessionId, extraServers, codeMode, TrustLevel.ASK);
    }

    /** The [trust] level adds its permission mode and allow and deny rules to the session's settings. */
    public SessionFiles write(String astrolabeSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode, TrustLevel trust) {
        return write(astrolabeSessionId, extraServers, codeMode, trust, SessionOverlay.EMPTY, null);
    }

    /**
     * The [overlay] adds its servers (except those Code Mode hides), settings, instructions and agents; [team] (may be null) only decides what the
     * instructions file holds. With an empty overlay the files are those of the overload without it.
     */
    public SessionFiles write(String astrolabeSessionId, Map<String, JsonNode> extraServers, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay, Team team) {
        var bearer = BEARER_PREFIX + server.sessionToken(astrolabeSessionId);
        var servers = new LinkedHashMap<>(extraServers);
        overlay.mcpServers().entrySet().stream().filter(entry -> !codeMode.deniedServers().contains(entry.getKey()))
                .forEach(entry -> servers.putIfAbsent(entry.getKey(), entry.getValue()));
        var config = mcpConfig(McpEndpoint.SERVER_NAME, server.mcpUrl(astrolabeSessionId), bearer, servers);
        if (codeMode != CodeModeAccess.OFF) {
            config.withObject("/mcpServers").putObject(CodeModeEndpoint.SERVER_NAME).put("type", "http").put("url", server.codeModeUrl(astrolabeSessionId))
                    .put("timeout", codeModeTimeoutMillis).putObject("headers").put("Authorization", bearer);
        }
        return write(astrolabeSessionId, config, codeMode, trust, overlay, team);
    }

    /**
     * The Assistant session gets only the app server in its mcp file; the project server and the project-session secret stay out of it.
     * The owner-only file holds the literal Bearer secret of the app server, never an environment reference.
     * The token alone reaches only auto-approved tools, every other tool needs the user's Allow in the app.
     */
    public SessionFiles writeAssistant(String astrolabeSessionId) {
        return write(astrolabeSessionId, mcpConfig(AssistantMcpServer.SERVER_NAME, server.assistantMcpUrl(), BEARER_PREFIX + server.assistantMcpToken(), Map.of()),
                CodeModeAccess.OFF, TrustLevel.ASK, SessionOverlay.EMPTY, null);
    }

    /** Value of {@link HookServer#SESSION_TOKEN_ENV}: the fallback for the helper, the files hold the token themselves because a restarted claude keeps their paths but not the environment. */
    public String sessionToken(String astrolabeSessionId) {
        return server.sessionToken(astrolabeSessionId);
    }

    private SessionFiles write(String astrolabeSessionId, ObjectNode mcpConfig, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay, Team team) {
        var file = paths.fileOf(astrolabeSessionId);
        var mcpFile = paths.mcpFileOf(astrolabeSessionId);
        Path promptFile;
        Path agentsPlugin;
        Path skillsPlugin;
        try {
            OwnerOnlyFiles.createDirectory(paths.hooksDir());
            OwnerOnlyFiles.write(paths.tokenFileOf(astrolabeSessionId), server.sessionToken(astrolabeSessionId));
            OwnerOnlyFiles.write(file, mapper.writeValueAsString(settingsBuilder.settings(astrolabeSessionId, codeMode, trust, overlay)));
            OwnerOnlyFiles.write(mcpFile, mapper.writeValueAsString(mcpConfig));
            promptFile = overlayFiles.writePrompt(astrolabeSessionId, overlay, team);
            agentsPlugin = overlayFiles.writeAgentsPlugin(astrolabeSessionId, overlay, team);
            skillsPlugin = overlayFiles.writeSkillsPlugin(astrolabeSessionId, overlay);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write hook settings " + file, e);
        }
        return new SessionFiles(file, mcpFile, promptFile, agentsPlugin, skillsPlugin, overlay);
    }

    public void delete(String astrolabeSessionId) {
        try {
            Files.deleteIfExists(paths.fileOf(astrolabeSessionId));
            Files.deleteIfExists(paths.mcpFileOf(astrolabeSessionId));
            Files.deleteIfExists(paths.tokenFileOf(astrolabeSessionId));
            Files.deleteIfExists(paths.promptFileOf(astrolabeSessionId));
            for (var plugin : new Path[] {paths.agentsPluginOf(astrolabeSessionId), paths.skillsPluginOf(astrolabeSessionId)}) {
                if (Files.exists(plugin)) {
                    TreeDeleter.delete(plugin);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete hook settings of " + astrolabeSessionId, e);
        }
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
}
