package com.github.krpkbhdn.ccmanager.codemode.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.codemode.policy.PermissionRules.Verdict;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudePermissionRulesTest {

    private static final SessionContext SESSION = new SessionContext(1, "s1");
    private static final String SERVER = "plugin_playwright_playwright";

    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectCodeModeService modes = mock(ProjectCodeModeService.class);
    private final HostEnvironment host = mock(HostEnvironment.class);
    private Path config;
    private Path project;
    private ClaudePermissionRules rules;

    @BeforeEach
    void setUp(@TempDir Path temp) throws IOException {
        config = Files.createDirectory(temp.resolve("config"));
        project = Files.createDirectories(temp.resolve("project/.claude"));
        when(host.claudeConfigDirectory()).thenReturn(config);
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", project.getParent(), Instant.now())));
        when(modes.directoryOf("s1")).thenReturn(Optional.empty());
        rules = new ClaudePermissionRules(projects, modes, host);
    }

    private void userSettings(String json) throws IOException {
        Files.writeString(config.resolve("settings.json"), json);
    }

    private Verdict verdict(String tool) {
        return rules.verdict(SESSION, SERVER, tool);
    }

    @Test
    void serverRuleOfTheSavedPluginNameAllowsTheWholeServer() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"]}}");

        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void wildcardRuleAllowsTheWholeServer() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright__*\"]}}");

        assertThat(verdict("browser_click")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void toolRuleAllowsOnlyThatTool() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright__browser_navigate\"]}}");

        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
        assertThat(verdict("browser_click")).isEqualTo(Verdict.UNDECIDED);
    }

    @Test
    void denyRuleFromAProjectFileWinsOverAnAllowRuleOfTheUserSettings() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"]}}");
        Files.writeString(project.resolve("settings.local.json"), "{\"permissions\":{\"deny\":[\"mcp__plugin_playwright_playwright__browser_evaluate\"]}}");

        assertThat(verdict("browser_evaluate")).isEqualTo(Verdict.DENY);
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void allowRulesOfProjectAndLocalFilesCount() throws IOException {
        Files.writeString(project.resolve("settings.json"), "{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"]}}");
        Files.writeString(project.resolve("settings.local.json"), "{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright__*\"]}}");

        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void askRuleOfTheServerOrTheToolStopsAnAllow() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"],\"ask\":[\"mcp__plugin_playwright_playwright__browser_click\"]}}");

        assertThat(verdict("browser_click")).isEqualTo(Verdict.ASK);
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
        Files.writeString(project.resolve("settings.json"), "{\"permissions\":{\"ask\":[\"mcp__plugin_playwright_playwright\"]}}");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);
    }

    @Test
    void otherWildcardDenyOrAskRulesOfTheServerFailClosedToAsk() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"],\"deny\":[\"mcp__plugin_playwright_playwright__browser_e*\"]}}");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);

        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"],\"ask\":[\"mcp__*\"]}}");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);

        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"],\"deny\":[\"mcp__other__x_*\"]}}");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void unreadableOrOversizedRepositoryFileTurnsAnAllowIntoAsk() throws IOException {
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"]}}");
        Files.writeString(project.resolve("settings.json"), "{ not json");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);

        Files.writeString(project.resolve("settings.json"), "x".repeat(CodeModeProperties.MAX_SETTINGS_BYTES + 1));
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);
    }

    @Test
    void missingSettingsAndOtherServersLeaveItUndecided() throws IOException {
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.UNDECIDED);
        userSettings("{\"permissions\":{\"allow\":[\"mcp__other\",\"mcp__plugin_playwright_playwright_extra__*\",\"Bash(ls)\"]}}");
        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.UNDECIDED);
    }

    @Test
    void unknownFolderNeverAllowsButStillDenies() throws IOException {
        when(projects.find(1)).thenReturn(Optional.empty());
        userSettings("{\"permissions\":{\"allow\":[\"mcp__plugin_playwright_playwright\"],\"deny\":[\"mcp__plugin_playwright_playwright__browser_evaluate\"]}}");

        assertThat(verdict("browser_navigate")).isEqualTo(Verdict.ASK);
        assertThat(verdict("browser_evaluate")).isEqualTo(Verdict.DENY);
    }
}
