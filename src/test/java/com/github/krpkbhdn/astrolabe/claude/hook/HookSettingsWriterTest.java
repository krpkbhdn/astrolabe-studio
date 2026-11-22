package com.github.krpkbhdn.astrolabe.claude.hook;

import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.APP_MCP_URL;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.APP_TOKEN;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.CODE_MODE;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.DEAD_PID;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.HELPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.HOOK_PROPERTIES;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.MAPPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.OVERLAY_PROPERTIES;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.PROJECT_MCP_ENTRY;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.STATUS_LINE_URL;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.TOKEN;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.WINDOWS_HELPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.agentMarkdown;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.installationWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.overlay.OverlayAgent;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import com.github.krpkbhdn.astrolabe.team.RoleDraft;
import com.github.krpkbhdn.astrolabe.team.Team;
import com.github.krpkbhdn.astrolabe.team.TeamRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

// mockStatic is thread-local, so the default separate-thread timeout would run tests off the mocking thread
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
class HookSettingsWriterTest {

    private final JsonMapper mapper = MAPPER;
    private HookSettingsWriter writer;
    private HookSettingsWriter windowsWriter;
    private HookServer server;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        server = HookTestFixtures.mockServer();
        writer = new HookSettingsWriter(server, new AppProperties(dataDir), mapper, HOOK_PROPERTIES, HELPER, installationWith("2.1.200"), CODE_MODE, OVERLAY_PROPERTIES);
        windowsWriter = new HookSettingsWriter(server, new AppProperties(dataDir), mapper, HOOK_PROPERTIES, WINDOWS_HELPER, installationWith("2.1.200"), CODE_MODE, OVERLAY_PROPERTIES);
    }

    @Test
    void rewritingASessionReplacesTheFileAndLeavesNoTemporaryFile() throws Exception {
        writer.write("abc-1");
        writer.write("abc-1");

        try (var files = Files.list(ownDir())) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .containsExactlyInAnyOrder("abc-1.json", "abc-1.mcp.json", "abc-1.token");
        }
    }

    private Path ownDir() {
        return dataDir.resolve("hooks").resolve(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    void writesHooksStatusLineAndMcpPermissionOnly() throws Exception {
        var file = writer.write("abc-1").settings();

        var json = mapper.readTree(Files.readString(file));
        assertThat(file).isEqualTo(ownDir().resolve("abc-1.json"));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("hooks", "statusLine", "permissions");
    }

    @Test
    void onPosixOnlyTheOwnerCanReadTheDirectoryAndFile() throws Exception {
        assumeTrue(dataDir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        var file = writer.write("abc-1").settings();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
    }

    @Test
    void theTokenFileHoldsOnlyTheTokenAndIsOwnerOnlyOnPosix() throws Exception {
        writer.write("abc-1");

        var tokenFile = ownDir().resolve("abc-1.token");
        assertThat(Files.readString(tokenFile)).isEqualTo(TOKEN);
        if (dataDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile))).isEqualTo("rw-------");
        }
    }

    @Test
    void noWrittenFileReferencesTheTokenVariable() throws Exception {
        var files = writer.write("abc-1", java.util.Map.of(), new CodeModeAccess(java.util.List.of(), java.util.List.of("jira")));

        assertThat(Files.readString(files.settings())).doesNotContain("ASTROLABE_HOOK_TOKEN");
        assertThat(Files.readString(files.mcpConfig())).doesNotContain("ASTROLABE_HOOK_TOKEN");
    }

    @Test
    void onWindowsTheStatusLineUsesTheShortPathAndSessionStartTheRealPathAndOnlyTheTokenFileIsAdded() throws Exception {
        var json = mapper.readTree(Files.readString(windowsWriter.write("abc-1").settings()));

        assertThat(json.path("statusLine").path("command").asString()).startsWith("C:/PROGRA~1/CLAUDE~1/astrolabe.exe --post-hook " + STATUS_LINE_URL + " --token-file ");
        var sessionStart = json.path("hooks").path("SessionStart").get(0).path("hooks").get(0);
        assertThat(sessionStart.path("command").asString()).isEqualTo("C:\\Program Files\\Astrolabe Studio\\astrolabe.exe");
        try (var files = Files.list(ownDir())) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactlyInAnyOrder("abc-1.json", "abc-1.mcp.json", "abc-1.token");
        }
    }

    @Test
    void assistantMcpFileHoldsTheLiteralSecretAndNoVariableAndProjectFilesNeverHoldIt() throws Exception {
        when(server.assistantMcpUrl()).thenReturn(APP_MCP_URL);
        when(server.assistantMcpToken()).thenReturn(APP_TOKEN);

        var files = writer.writeAssistant("abc-1");

        var text = Files.readString(files.mcpConfig());
        var entry = mapper.readTree(text).path("mcpServers").path("astrolabe-app");
        assertThat(entry.path("url").asString()).isEqualTo(APP_MCP_URL);
        assertThat(entry.path("headers").path("Authorization").asString()).isEqualTo("Bearer " + APP_TOKEN);
        assertThat(text).doesNotContain("ASTROLABE_ASSISTANT_TOKEN");
        assertThat(Files.readString(files.settings())).doesNotContain(APP_TOKEN);
        if (dataDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(files.mcpConfig()))).isEqualTo("rw-------");
        }
        var project = writer.write("abc-1");
        assertThat(Files.readString(project.mcpConfig())).doesNotContain(APP_TOKEN).doesNotContain(APP_MCP_URL);
        assertThat(Files.readString(project.settings())).doesNotContain(APP_TOKEN);
    }

    @Test
    void projectSessionsAndTheAssistantBothDenyReadingTheHooksDirectory() throws Exception {
        when(server.assistantMcpUrl()).thenReturn(APP_MCP_URL);
        var deny = mapper.readTree(Files.readString(writer.write("abc-1").settings())).path("permissions").path("deny");
        var assistant = mapper.readTree(Files.readString(writer.writeAssistant("abc-1").settings())).path("permissions");

        assertThat(deny.get(0).asString()).startsWith("Read(/").endsWith("/hooks/**)");
        assertThat(assistant.path("deny").get(0).asString()).isEqualTo(deny.get(0).asString());
        var windowsDeny = mapper.readTree(Files.readString(windowsWriter.write("abc-1").settings())).path("permissions").path("deny");
        assertThat(windowsDeny.get(0).asString()).doesNotContain(":", "\\").endsWith("/hooks/**)");
    }

    @Test
    void writesAnMcpConfigPointingAtTheMcpEndpointAndPreApprovesItsTools() throws Exception {
        var files = writer.write("abc-1");

        assertThat(files.mcpConfig()).isEqualTo(ownDir().resolve("abc-1.mcp.json"));
        assertThat(mapper.readTree(Files.readString(files.mcpConfig())).path("mcpServers").path("astrolabe").toString())
                .isEqualTo(PROJECT_MCP_ENTRY);
        assertThat(Files.readString(files.mcpConfig())).doesNotContain("secret");
        assertThat(mapper.readTree(Files.readString(files.settings())).path("permissions").path("allow").toString()).isEqualTo("[\"mcp__astrolabe__list_run_configurations\"]");
    }

    @Test
    void extraServersAreMergedButTheAppsOwnServerNameStaysReserved() throws Exception {
        var extra = new java.util.LinkedHashMap<String, tools.jackson.databind.JsonNode>();
        extra.put("db", mapper.readTree("{\"command\":\"db-mcp\"}"));
        extra.put("astrolabe", mapper.readTree("{\"command\":\"evil\"}"));

        var servers = mapper.readTree(Files.readString(writer.write("abc-1", extra).mcpConfig())).path("mcpServers");

        assertThat(servers.path("db").toString()).isEqualTo("{\"command\":\"db-mcp\"}");
        assertThat(servers.path("astrolabe").toString()).isEqualTo(PROJECT_MCP_ENTRY);
    }

    @Test
    void onlyTheLauncherWritesSessionFilesSoEveryLaunchGetsTheTokenVariable() throws Exception {
        try (var sources = Files.walk(Path.of("src/main"))) {
            var users = sources.filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".kt"))
                    .filter(file -> !file.getFileName().toString().startsWith("HookSettingsWriter"))
                    .filter(file -> read(file).contains("HookSettingsWriter")).map(file -> file.getFileName().toString()).toList();

            assertThat(users).containsExactly("ClaudeLauncher.java");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void onPosixTheMcpConfigIsOwnerOnly()throws Exception {
        assumeTrue(dataDir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        var file = writer.write("abc-1").mcpConfig();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void deleteRemovesFileAndRejectsPathTraversal() {
        var file = writer.write("abc-1").settings();
        var tokenFile = ownDir().resolve("abc-1.token");
        assertThat(tokenFile).exists();

        writer.delete("abc-1");

        assertThat(file).doesNotExist();
        assertThat(tokenFile).doesNotExist();
        assertThat(ownDir()).isEmptyDirectory();
        assertThatThrownBy(() -> writer.write("../evil")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupRemovesTheFilesOfDeadProcessesAndLooseOldOnesButKeepsLiveOnes() throws Exception {
        var live = writer.write("abc-1").settings();
        var deadDir = Files.createDirectories(dataDir.resolve("hooks").resolve(DEAD_PID));
        var deadFile = Files.writeString(deadDir.resolve("old.json"), "{}");
        var loose = Files.writeString(dataDir.resolve("hooks").resolve("legacy.json"), "{}");

        writer.deleteStaleFiles();

        assertThat(deadDir).doesNotExist();
        assertThat(deadFile).doesNotExist();
        assertThat(loose).doesNotExist();
        assertThat(live).exists();
    }

    @Test
    void startupDoesNotFailWhenAStaleEntryCannotBeDeleted() throws Exception {
        var hooks = dataDir.resolve("hooks");
        Files.createDirectories(hooks.resolve(DEAD_PID));
        var undeletable = Files.createDirectories(hooks.resolve("locked").resolve("child"));
        try (var deleter = org.mockito.Mockito.mockStatic(com.github.krpkbhdn.astrolabe.workspace.TreeDeleter.class)) {
            deleter.when(() -> com.github.krpkbhdn.astrolabe.workspace.TreeDeleter.delete(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("secret detail")));

            org.assertj.core.api.Assertions.assertThatCode(() -> writer.deleteStaleFiles()).doesNotThrowAnyException();
        }
        assertThat(undeletable).exists();
    }

    @Test
    void startupKeepsTheDirectoryOfALivePidHoweverOldItIs() throws Exception {
        var otherPid = ProcessHandle.current().parent();
        assumeTrue(otherPid.isPresent());
        var directory = Files.createDirectories(dataDir.resolve("hooks").resolve(String.valueOf(otherPid.get().pid())));
        writer.deleteStaleFiles();
        assertThat(directory).exists();

        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        writer.deleteStaleFiles();

        assertThat(directory).exists();
    }

    @Test
    void aSecondWriterInTheSameProcessLeavesTheRunningOnesFilesAlone() throws Exception {
        var live = writer.write("abc-1").settings();

        new HookSettingsWriter(server, new AppProperties(dataDir), mapper, HOOK_PROPERTIES, HELPER, installationWith("2.1.200"), CODE_MODE, OVERLAY_PROPERTIES).deleteStaleFiles();

        assertThat(live).exists();
    }

    @Test
    void codeModeAddsItsServerHidesTheListedOnesAndPreApprovesOnlyTheReadOnlyTools() throws Exception {
        when(server.codeModeUrl("abc-1")).thenReturn("http://127.0.0.1:1/code/abc-1");
        var access = new CodeModeAccess(java.util.List.of("jira", "bad*", "astrolabe"), java.util.List.of("jira", "files", "x*", "a b"));

        var files = writer.write("abc-1", java.util.Map.of(), access);
        var settings = mapper.readTree(Files.readString(files.settings()));
        var entry = mapper.readTree(Files.readString(files.mcpConfig())).path("mcpServers").path("astrolabe-code");

        assertThat(entry.path("url").asString()).isEqualTo("http://127.0.0.1:1/code/abc-1");
        assertThat(entry.path("headers").path("Authorization").asString()).isEqualTo("Bearer " + TOKEN);
        assertThat(entry.path("timeout").asLong()).isEqualTo(Duration.ofHours(1).plusSeconds(30).toMillis());
        assertThat(settings.path("disabledMcpjsonServers").toString()).isEqualTo("[\"jira\"]");
        assertThat(settings.path("permissions").path("allow").toString())
                .isEqualTo("[\"mcp__astrolabe__list_run_configurations\",\"mcp__astrolabe-code__search_tools\",\"mcp__astrolabe-code__list_servers\"]");
        assertThat(settings.path("permissions").path("deny").toString()).contains("\"mcp__jira\"", "\"mcp__files\"").doesNotContain("x*", "a b");
        assertThat(settings.has("hooks")).isTrue();
    }

    @Test
    void codeModeOffLeavesSettingsAndMcpConfigUntouched() throws Exception {
        var files = writer.write("abc-1", java.util.Map.of(), CodeModeAccess.OFF);

        assertThat(Files.readString(files.mcpConfig())).doesNotContain("astrolabe-code");
        assertThat(mapper.readTree(Files.readString(files.settings())).has("disabledMcpjsonServers")).isFalse();
    }

    @Test
    void aSessionWithoutCodeModeNeverGetsATimeoutFieldNorTheCodeModeServerNorItsPermissions() throws Exception {
        var files = writer.write("abc-1", java.util.Map.of(), CodeModeAccess.OFF);
        var servers = mapper.readTree(Files.readString(files.mcpConfig())).path("mcpServers");
        var settings = Files.readString(files.settings());

        assertThat(servers.propertyNames()).doesNotContain("astrolabe-code");
        servers.forEach(entry -> assertThat(entry.has("timeout")).isFalse());
        assertThat(settings).doesNotContain("astrolabe-code").doesNotContain("disabledMcpjsonServers");
    }

    private SessionOverlay overlay(String settings, String instructions, String servers, java.util.List<OverlayAgent> agents) throws Exception {
        return HookTestFixtures.overlay(dataDir, settings, instructions, servers, agents);
    }

    private SessionFiles writeWith(SessionOverlay overlay, CodeModeAccess access, Team team) {
        return writer.write("abc-1", java.util.Map.of(), access, TrustLevel.ASK, overlay, team);
    }

    @Test
    void anEmptyOverlayWritesTheSameFilesAsNoOverlay() throws Exception {
        var plain = writer.write("abc-1");
        var settings = Files.readString(plain.settings());
        var mcp = Files.readString(plain.mcpConfig());

        var withEmpty = writeWith(SessionOverlay.EMPTY, CodeModeAccess.OFF, null);

        assertThat(Files.readString(withEmpty.settings())).isEqualTo(settings);
        assertThat(Files.readString(withEmpty.mcpConfig())).isEqualTo(mcp);
        assertThat(withEmpty.promptFile()).isNull();
        assertThat(withEmpty.agentsPlugin()).isNull();
    }

    @Test
    void overlayServersJoinTheMcpConfigExceptTheOnesCodeModeHidesAndNeverReplaceTheAppServer() throws Exception {
        when(server.codeModeUrl("abc-1")).thenReturn("http://127.0.0.1:1/code/abc-1");
        var overlay = overlay("{}", "", "{\"keep\":{\"command\":\"a\"},\"hidden\":{\"command\":\"b\"},\"astrolabe\":{\"command\":\"evil\"}}", java.util.List.of());

        var servers = mapper.readTree(Files.readString(writeWith(overlay, new CodeModeAccess(java.util.List.of(), java.util.List.of("hidden")), null).mcpConfig())).path("mcpServers");

        assertThat(servers.has("keep")).isTrue();
        assertThat(servers.has("hidden")).isFalse();
        assertThat(servers.path("astrolabe").toString()).isEqualTo(PROJECT_MCP_ENTRY);
    }

    @Test
    void overlayInstructionsAndTheTeamAddendumShareOneOwnerOnlyPromptFileThatIsDeletedWithTheSession() throws Exception {
        var team = new Team(1, "T", java.util.List.of(new TeamRole(1, new RoleDraft("boss", "d", "Lead it.", null, null, null, true), 0),
                new TeamRole(2, new RoleDraft("dev", "Writes code", "Code.", null, null, null, false), 1)));
        var files = writeWith(overlay("{}", "Use tabs.", "{}", java.util.List.of()), CodeModeAccess.OFF, team);

        var content = Files.readString(files.promptFile());
        assertThat(content).startsWith("Lead it.").contains("lead of the team \"T\"").endsWith("Use tabs.");
        assertThat(files.promptFile().getFileName().toString()).isEqualTo("abc-1.prompt.md");
        if (Files.getFileStore(dataDir).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(files.promptFile()))).isEqualTo("rw-------");
        }

        writer.delete("abc-1");

        assertThat(files.promptFile()).doesNotExist();
    }

    @Test
    void overlayAgentsTooLongForTheCommandLineBecomeAPluginDirectoryDeletedWithTheSession() throws Exception {
        var big = agentMarkdown("x".repeat(25_000));
        var agent = OverlayAgent.parse("big.md", big).orElseThrow();
        var small = OverlayAgent.parse("small.md", agentMarkdown("ok")).orElseThrow();

        var files = writeWith(overlay("{}", "", "{}", java.util.List.of(agent, small)), CodeModeAccess.OFF, null);

        assertThat(files.agentsPlugin().resolve(".claude-plugin").resolve("plugin.json")).exists();
        assertThat(files.agentsPlugin().resolve("agents").resolve("big.md")).hasContent(big);
        assertThat(files.agentsPlugin().resolve("agents").resolve("small.md")).exists();
        assertThat(files.inlineAgents()).isEmpty();

        writer.delete("abc-1");

        assertThat(files.agentsPlugin()).doesNotExist();
    }

    @Test
    void overlaySkillsAreCopiedIntoAPluginDirectoryFirstNameWinsAndDeletedWithTheSession() throws Exception {
        var first = dataDir.resolve("ctx1");
        var second = dataDir.resolve("ctx2");
        Files.createDirectories(first.resolve(".claude/skills/a/refs"));
        Files.createDirectories(second.resolve(".claude/skills/a"));
        Files.createDirectories(second.resolve(".claude/skills/b"));
        Files.writeString(first.resolve(".claude/skills/a/SKILL.md"), "first a");
        Files.writeString(first.resolve(".claude/skills/a/refs/x.md"), "ref");
        Files.writeString(second.resolve(".claude/skills/a/SKILL.md"), "second a");
        Files.writeString(second.resolve(".claude/skills/b/SKILL.md"), "b");
        var base = overlay("{}", "", "{}", java.util.List.of());
        var withSkills = new SessionOverlay(base.mcpServers(), base.settings(), "", base.agents(), java.util.List.of(first, second), base.roots());

        var files = writeWith(withSkills, CodeModeAccess.OFF, null);

        var plugin = files.skillsPlugin();
        assertThat(plugin.getFileName().toString()).isEqualTo("abc-1.skills");
        assertThat(Files.readString(plugin.resolve(".claude-plugin/plugin.json"))).contains("astrolabe-skill");
        assertThat(plugin.resolve("skills/a/SKILL.md")).hasContent("first a");
        assertThat(plugin.resolve("skills/a/refs/x.md")).hasContent("ref");
        assertThat(plugin.resolve("skills/b/SKILL.md")).hasContent("b");

        writer.delete("abc-1");

        assertThat(plugin).doesNotExist();
    }
}
