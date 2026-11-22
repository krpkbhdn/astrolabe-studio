package com.github.krpkbhdn.astrolabe.claude.hook;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallation;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.install.LoginState;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.overlay.OverlayAgent;
import com.github.krpkbhdn.astrolabe.overlay.OverlayProperties;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class HookTestFixtures {

    static final String URL = "http://127.0.0.1:5555/hook/abc-1";
    static final String STATUS_LINE_URL = "http://127.0.0.1:5555/statusline/abc-1";
    static final String MCP_URL = "http://127.0.0.1:5555/mcp/abc-1";
    static final String TOKEN = "session-token-abc";
    static final String PROJECT_MCP_ENTRY = "{\"type\":\"http\",\"url\":\"" + MCP_URL + "\",\"headers\":{\"Authorization\":\"Bearer " + TOKEN + "\"}}";
    static final String APP_MCP_URL = "http://127.0.0.1:5555/assistant-mcp";
    static final String APP_TOKEN = "apptokenvalue";
    static final String DEAD_PID = String.valueOf(Integer.MAX_VALUE);

    static final String APP = "/opt/astrolabe/astrolabe-app";
    static final HookHelperCommand HELPER = new HookHelperCommand(Optional.of(APP), "/unused", false, path -> path, false);
    static final HookHelperCommand WINDOWS_HELPER = new HookHelperCommand(Optional.of("C:\\Program Files\\Astrolabe Studio\\astrolabe.exe"), "/unused", true,
            path -> path.startsWith("C:\\Program") ? "C:/PROGRA~1/CLAUDE~1/astrolabe.exe" : path, false);
    static final HookProperties HOOK_PROPERTIES = new HookProperties(10, 1024, 500, Duration.ofSeconds(590), 10, 1000, 1000);
    static final OverlayProperties OVERLAY_PROPERTIES = new OverlayProperties(Duration.ofDays(1), 20_000);
    static final CodeModeProperties CODE_MODE = TestProperties.of(
            Duration.ofMinutes(1), 1000, 1000, 10, Duration.ofMinutes(1), "img", "64m", 1, Duration.ofDays(1), Duration.ofSeconds(10));
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HookTestFixtures() {
    }

    static HookServer mockServer() {
        var server = mock(HookServer.class);
        when(server.hookUrl("abc-1")).thenReturn(URL);
        when(server.statusLineUrl("abc-1")).thenReturn(STATUS_LINE_URL);
        when(server.mcpUrl("abc-1")).thenReturn(MCP_URL);
        when(server.sessionToken("abc-1")).thenReturn(TOKEN);
        return server;
    }

    static ClaudeInstallationService installationWith(String version) {
        var installation = mock(ClaudeInstallationService.class);
        var detected = new ClaudeInstallation(version != null, null, version, LoginState.UNKNOWN, null);
        when(installation.current()).thenReturn(detected);
        when(installation.recheck()).thenReturn(detected);
        return installation;
    }

    static SessionPaths sessionPaths(Path dataDir) {
        var hooksRoot = dataDir.resolve("hooks");
        return new SessionPaths(hooksRoot, hooksRoot.resolve(String.valueOf(ProcessHandle.current().pid())));
    }

    static SessionOverlay overlay(Path dataDir, String settings, String instructions, String servers, List<OverlayAgent> agents) throws Exception {
        var serverMap = new LinkedHashMap<String, JsonNode>();
        MAPPER.readTree(servers).properties().forEach(entry -> serverMap.put(entry.getKey(), entry.getValue()));
        var agentMap = new LinkedHashMap<String, OverlayAgent>();
        agents.forEach(agent -> agentMap.put(agent.name(), agent));
        return new SessionOverlay(serverMap, (ObjectNode) MAPPER.readTree(settings), instructions, agentMap, List.of(),
                List.of(dataDir.resolve("projects").resolve("1")));
    }

    static String agentMarkdown(String body) {
        return String.join("\n", "---", "description: d", "---", body);
    }
}
