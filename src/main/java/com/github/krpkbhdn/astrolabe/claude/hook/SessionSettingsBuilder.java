package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.claude.event.HookEventType;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeVersion;
import com.github.krpkbhdn.astrolabe.claude.mcp.McpEndpoint;
import com.github.krpkbhdn.astrolabe.codemode.mcp.CodeModeEndpoint;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import com.github.krpkbhdn.astrolabe.overlay.SettingsMerge;
import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the content of a session's --settings file: hooks, statusLine, permissions and the overlay allowlist merge. */
class SessionSettingsBuilder {

    private static final Logger log = LoggerFactory.getLogger(SessionSettingsBuilder.class);
    private static final String LIST_RUN_CONFIGURATIONS_TOOL = "list_run_configurations";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int HOOK_TIMEOUT_SECONDS = 5;
    /** Claude Code that knows the hook {@code args} field (changelog 2.1.139); an older one would run the command bare and start a second app. */
    private static final String EXEC_FORM_SINCE = "2.1.139";
    /** WorktreeCreate replaces git behaviour, MessageDisplay is a token firehose, the rest never fire in sessions. */
    private static final Set<HookEventType> NOT_SUBSCRIBED = EnumSet.of(HookEventType.SETUP, HookEventType.WORKTREE_CREATE,
            HookEventType.WORKTREE_REMOVE, HookEventType.MESSAGE_DISPLAY, HookEventType.UNKNOWN);

    private final HookServer server;
    private final JsonMapper mapper;
    private final HookHelperCommand helper;
    private final ClaudeInstallationService installation;
    private final SessionPaths paths;
    private final int decisionTimeoutSeconds;
    private final boolean windows;
    private final AtomicBoolean skipWarned = new AtomicBoolean();

    SessionSettingsBuilder(HookServer server, JsonMapper mapper, HookHelperCommand helper, ClaudeInstallationService installation, SessionPaths paths,
            int decisionTimeoutSeconds) {
        this.server = server;
        this.mapper = mapper;
        this.helper = helper;
        this.installation = installation;
        this.paths = paths;
        this.decisionTimeoutSeconds = decisionTimeoutSeconds;
        this.windows = helper.windows();
    }

    /**
     * Only enabledPlugins and the deny and ask rules of the overlay settings are merged under the app's (an allowlist: defaultMode, allow rules, env,
     * helpers and the like never reach a launch); every overlay folder becomes edit-protected and its mcp.json and settings.json unreadable.
     * shortcut: a Bash redirect or cat is not covered by these rules, only Claude's own permission dialog guards it.
     */
    ObjectNode settings(String astrolabeSessionId, CodeModeAccess codeMode, TrustLevel trust, SessionOverlay overlay) {
        var app = settings(astrolabeSessionId, codeMode, trust);
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

    ObjectNode settings(String astrolabeSessionId, CodeModeAccess codeMode, TrustLevel trust) {
        var hooks = mapper.createObjectNode();
        Arrays.stream(HookEventType.values())
                .filter(type -> !NOT_SUBSCRIBED.contains(type))
                .filter(type -> type != HookEventType.SESSION_START || execFormSupported(astrolabeSessionId))
                .forEach(type -> hooks.putArray(type.wireName()).addObject().putArray("hooks").add(handler(type, astrolabeSessionId)));
        var settings = mapper.createObjectNode();
        settings.set("hooks", hooks);
        helper.shellCommand(server.statusLineUrl(astrolabeSessionId), paths.tokenFileOf(astrolabeSessionId)).ifPresent(command -> settings.set("statusLine", statusLine(command)));
        var permissions = settings.putObject("permissions");
        var allow = permissions.putArray("allow").add("mcp__" + McpEndpoint.SERVER_NAME + "__" + LIST_RUN_CONFIGURATIONS_TOOL);
        TrustPermissions.allow(trust).forEach(allow::add);
        var deny = permissions.putArray("deny");
        deny.add("Read(" + absoluteRule(paths.hooksRoot()) + "/**)");
        TrustPermissions.deny(trust).forEach(deny::add);
        TrustPermissions.defaultMode(trust).ifPresent(mode -> permissions.put("defaultMode", mode));
        if (codeMode != CodeModeAccess.OFF) {
            applyCodeMode(settings, permissions, deny, codeMode);
        }
        return settings;
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

    private boolean execFormSupported(String astrolabeSessionId) {
        if (helper.execForm(server.hookUrl(astrolabeSessionId), paths.tokenFileOf(astrolabeSessionId)).isEmpty()) {
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

    private ObjectNode handler(HookEventType type, String astrolabeSessionId) {
        if (type == HookEventType.SESSION_START) {
            return commandHandler(astrolabeSessionId);
        }
        return httpHandler(astrolabeSessionId, type.awaitsDecision() ? decisionTimeoutSeconds : HOOK_TIMEOUT_SECONDS);
    }

    /** The token is written literally: after claude restarts its session (agent view) the environment is foreign but the settings file stays. */
    private ObjectNode httpHandler(String astrolabeSessionId, int timeoutSeconds) {
        var handler = mapper.createObjectNode().put("type", "http").put("url", server.hookUrl(astrolabeSessionId)).put("timeout", timeoutSeconds);
        handler.putObject("headers").put("Authorization", BEARER_PREFIX + server.sessionToken(astrolabeSessionId));
        return handler;
    }

    /** SessionStart does not accept http handlers; exec form (no shell, nothing to quote) and async so a stalled helper can never delay startup. */
    private ObjectNode commandHandler(String astrolabeSessionId) {
        var exec = helper.execForm(server.hookUrl(astrolabeSessionId), paths.tokenFileOf(astrolabeSessionId)).orElseThrow();
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
}
