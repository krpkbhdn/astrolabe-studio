package com.github.krpkbhdn.astrolabe.claude.hook;

import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.HELPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.MAPPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.STATUS_LINE_URL;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.TOKEN;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.URL;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.installationWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.event.HookEventType;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallation;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.install.LoginState;
import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SessionSettingsBuilderTest {

    private static final int DECISION_TIMEOUT_SECONDS = 600;

    private final JsonMapper mapper = MAPPER;
    private SessionSettingsBuilder builder;
    private HookServer server;
    private SessionPaths paths;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        server = HookTestFixtures.mockServer();
        paths = HookTestFixtures.sessionPaths(dataDir);
        builder = builderWith(HELPER, installationWith("2.1.200"), DECISION_TIMEOUT_SECONDS);
    }

    private SessionSettingsBuilder builderWith(HookHelperCommand helper, ClaudeInstallationService installation, int decisionTimeoutSeconds) {
        return new SessionSettingsBuilder(server, mapper, helper, installation, paths, decisionTimeoutSeconds);
    }

    private Path ownDir() {
        return paths.hooksDir();
    }

    private JsonNode permissionsFor(TrustLevel level) {
        return builder.settings("abc-1", CodeModeAccess.OFF, level).path("permissions");
    }

    @Test
    void askSettingsHaveNoDefaultModeAndNoTrustRules() throws Exception {
        var permissions = permissionsFor(com.github.krpkbhdn.astrolabe.project.TrustLevel.ASK);
        assertThat(permissions.has("defaultMode")).isFalse();
        assertThat(permissions.path("allow").toString()).doesNotContain("\"Read\"");
        assertThat(permissions.path("deny").toString()).doesNotContain(".env");
    }

    @Test
    void trustedSettingsAcceptEditsAndKeepSecretDenies() throws Exception {
        var permissions = permissionsFor(com.github.krpkbhdn.astrolabe.project.TrustLevel.TRUSTED);
        assertThat(permissions.path("defaultMode").asString()).isEqualTo("acceptEdits");
        assertThat(permissions.path("allow").toString()).contains("Bash(git status)").doesNotContain("\"Read\"", "gradlew");
        assertThat(permissions.path("deny").toString()).contains("Read(**/.env*)");
    }

    @Test
    void autonomousSettingsBypassPermissionsButKeepDenies() throws Exception {
        var permissions = permissionsFor(com.github.krpkbhdn.astrolabe.project.TrustLevel.AUTONOMOUS);
        assertThat(permissions.path("defaultMode").asString()).isEqualTo("bypassPermissions");
        assertThat(permissions.path("deny").toString()).contains("Read(**/.env*)", "Bash(rm -rf *)", "Bash(git push --force*)", "Bash(git reset --hard*)");
    }

    @Test
    void sessionStartNeedsAClaudeThatKnowsTheExecFormArgsField() throws Exception {
        for (var version : java.util.Arrays.asList("2.1.138", "2.0.0", null)) {
            var old = builderWith(HELPER, installationWith(version), DECISION_TIMEOUT_SECONDS);

            var settings = old.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK);

            assertThat(settings.path("hooks").has("SessionStart")).as(String.valueOf(version)).isFalse();
            assertThat(settings.has("statusLine")).as(String.valueOf(version)).isTrue();
        }
        var exact = builderWith(HELPER, installationWith("2.1.139"), DECISION_TIMEOUT_SECONDS);
        assertThat(exact.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks").has("SessionStart")).isTrue();
    }

    @Test
    void anUnknownCachedVersionIsDetectedAgainOnceBeforeDecidingAboutSessionStart() throws Exception {
        var installation = mock(ClaudeInstallationService.class);
        when(installation.current()).thenReturn(new ClaudeInstallation(false, null, null, LoginState.UNKNOWN, null));
        when(installation.recheck()).thenReturn(new ClaudeInstallation(true, null, "2.1.200", LoginState.UNKNOWN, null));
        var recovered = builderWith(HELPER, installation, DECISION_TIMEOUT_SECONDS);

        var settings = recovered.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK);

        assertThat(settings.path("hooks").has("SessionStart")).isTrue();
        org.mockito.Mockito.verify(installation).recheck();
    }

    @Test
    void decisionHookTimeoutFollowsTheConfiguredHoldWithAMargin() throws Exception {
        var longHold = builderWith(HELPER, installationWith("2.1.200"), 910);

        var hooks = longHold.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks");

        assertThat(hooks.path("PermissionRequest").get(0).path("hooks").get(0).path("timeout").asInt()).isEqualTo(910);
    }

    @Test
    void subscribesToEveryEventExceptTheDangerousOnes() throws Exception {
        var hooks = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks");

        assertThat(hooks.propertyNames()).contains("PreToolUse", "PermissionRequest", "Stop", "SessionEnd", "SessionStart")
                .doesNotContain("WorktreeCreate", "WorktreeRemove", "Setup", "MessageDisplay", "");
        assertThat(hooks.size()).isEqualTo(HookEventType.values().length - 5);
    }

    @Test
    void usesShortTimeoutHttpHandlersWithoutMatcher() throws Exception {
        var entry = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks").path("PreToolUse").get(0);

        assertThat(entry.has("matcher")).isFalse();
        var handler = entry.path("hooks").get(0);
        assertThat(handler.path("type").asString()).isEqualTo("http");
        assertThat(handler.path("url").asString()).isEqualTo(URL);
        assertThat(handler.path("timeout").asInt()).isEqualTo(5);
        assertThat(handler.path("headers").toString()).isEqualTo("{\"Authorization\":\"Bearer " + TOKEN + "\"}");
        assertThat(handler.has("allowedEnvVars")).isFalse();
        assertThat(handler.toString()).doesNotContain("ASTROLABE_HOOK_TOKEN");
    }

    @Test
    void permissionRequestAndElicitationWaitUpToTenMinutes() throws Exception {
        var hooks = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks");

        assertThat(hooks.path("PermissionRequest").get(0).path("hooks").get(0).path("timeout").asInt()).isEqualTo(600);
        assertThat(hooks.path("Elicitation").get(0).path("hooks").get(0).path("timeout").asInt()).isEqualTo(600);
    }

    @Test
    void sessionStartIsAnAsyncCommandRunningTheHelperWithTheTokenFileNotTheToken() throws Exception {
        var handler = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("hooks").path("SessionStart").get(0)
                .path("hooks").get(0);

        assertThat(handler.path("type").asString()).isEqualTo("command");
        assertThat(handler.path("command").asString()).isEqualTo("/opt/astrolabe/astrolabe-app");
        assertThat(handler.path("args").get(2).asString()).isEqualTo("--token-file");
        assertThat(handler.path("args").get(3).asString()).isEqualTo(ownDir().resolve("abc-1.token").toAbsolutePath().toString());
        assertThat(handler.path("args").toString()).doesNotContain(TOKEN);
        assertThat(handler.path("async").asBoolean()).isTrue();
    }

    @Test
    void withoutAKnownLauncherThereIsNoStatusLineAndNoSessionStart() throws Exception {
        var unknown = builderWith(new HookHelperCommand(Optional.empty(), "/unused", false, path -> path, false), installationWith("2.1.200"), DECISION_TIMEOUT_SECONDS);

        var settings = unknown.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK);

        assertThat(settings.has("statusLine")).isFalse();
        assertThat(settings.path("hooks").has("SessionStart")).isFalse();
    }

    @Test
    void statusLineCommandRunsTheHelperAgainstTheStatusLineEndpoint() throws Exception {
        var statusLine = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK).path("statusLine");

        var tokenFile = ownDir().resolve("abc-1.token").toAbsolutePath().toString();
        var tokenFileWord = tokenFile.indexOf('\\') >= 0 ? "'" + tokenFile + "'" : tokenFile;

        assertThat(statusLine.path("type").asString()).isEqualTo("command");
        assertThat(statusLine.path("command").asString())
                .isEqualTo("/opt/astrolabe/astrolabe-app --post-hook " + STATUS_LINE_URL + " --token-file " + tokenFileWord);
        assertThat(statusLine.path("command").asString()).doesNotContain(TOKEN);
    }

    @Test
    void aPluginServerIsDeniedByItsCanonicalToolPrefixAndNeverDisabledAsAProjectServer() throws Exception {
        var access = new CodeModeAccess(java.util.List.of(), java.util.List.of("plugin_playwright_playwright", "playwright"));

        var settings = builder.settings("abc-1", access, TrustLevel.ASK);

        assertThat(settings.path("permissions").path("deny").toString()).contains("\"mcp__plugin_playwright_playwright\"", "\"mcp__playwright\"");
        assertThat(settings.has("disabledMcpjsonServers")).isFalse();
    }

    @Test
    void overlaySettingsAreMergedUnderTheAppsAndTheOverlayFolderIsEditProtected() throws Exception {
        var overlay = HookTestFixtures.overlay(dataDir, "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"evil\"}]}]},\"statusLine\":{\"type\":\"command\",\"command\":\"evil\"},"
                + "\"permissions\":{\"allow\":[\"Bash(ls)\"],\"deny\":[\"Read(secret)\"],\"ask\":[\"Bash(rm *)\"],\"defaultMode\":\"bypassPermissions\"},\"enabledPlugins\":{\"a@b\":true},"
                + "\"env\":{\"X\":\"1\"},\"apiKeyHelper\":\"evil\",\"enableAllProjectMcpServers\":true,\"disableAllHooks\":true,\"sandbox\":{\"enabled\":false}}", "", "{}", java.util.List.of());

        var merged = builder.settings("abc-1", CodeModeAccess.OFF, TrustLevel.ASK, overlay);

        assertThat(merged.path("hooks").path("Stop").toString()).doesNotContain("evil").contains(URL);
        assertThat(merged.path("statusLine").toString()).doesNotContain("evil");
        assertThat(merged.path("permissions").path("allow").toString()).contains("mcp__astrolabe__list_run_configurations").doesNotContain("Bash(ls)");
        assertThat(merged.path("permissions").path("deny").toString()).contains("Read(secret)", "Read(/", "Edit(/").contains("/projects/1/**)")
                .contains("/projects/1/mcp.json)", "/projects/1/settings.json)");
        assertThat(merged.path("permissions").path("ask").toString()).contains("Bash(rm *)");
        assertThat(merged.path("permissions").has("defaultMode")).isFalse();
        assertThat(merged.path("enabledPlugins").path("a@b").asBoolean()).isTrue();
        assertThat(merged.has("env") || merged.has("apiKeyHelper") || merged.has("enableAllProjectMcpServers") || merged.has("disableAllHooks") || merged.has("sandbox")).isFalse();
    }
}
