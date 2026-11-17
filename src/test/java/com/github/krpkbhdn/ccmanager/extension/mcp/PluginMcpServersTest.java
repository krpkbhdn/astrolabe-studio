package com.github.krpkbhdn.ccmanager.extension.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer;
import com.github.krpkbhdn.ccmanager.git.GitRunner;
import com.github.krpkbhdn.ccmanager.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMcpServersTest {

    @TempDir
    Path home;

    @TempDir
    Path project;

    private FakeHostEnvironment host;
    private PluginMcpServers plugins;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, home).withEnv("TOKEN", "s3cret");
        config = home.resolve(".claude");
        Files.createDirectories(config.resolve("plugins"));
        plugins = new PluginMcpServers(host, new McpConfigReader(host, new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class)));
    }

    private Path install(String name, String mcpJson, String manifestJson) throws IOException {
        var path = config.resolve("plugins/cache/market/" + name + "/" + Math.abs(mcpJson.hashCode() + manifestJson.hashCode()));
        Files.createDirectories(path.resolve(".claude-plugin"));
        Files.writeString(path.resolve(".claude-plugin/plugin.json"), manifestJson);
        if (!mcpJson.isEmpty()) {
            Files.writeString(path.resolve(".mcp.json"), mcpJson);
        }
        host.withDirectory(path);
        return path;
    }

    private void installed(String json) throws IOException {
        Files.writeString(config.resolve("plugins/installed_plugins.json"), "{\"version\":2,\"plugins\":" + json + "}");
    }

    private static String entry(String scope, Path path, String extra) {
        return "{\"scope\":\"" + scope + "\",\"installPath\":" + quote(path) + extra + "}";
    }

    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }

    private void enable(Path settings, String json) throws IOException {
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"enabledPlugins\":" + json + "}");
    }

    @Test
    void readsABareMcpJsonOfAnEnabledUserPlugin() throws IOException {
        var path = install("playwright", "{\"playwright\":{\"command\":\"npx\",\"args\":[\"@playwright/mcp@latest\"]}}", "{\"name\":\"playwright\"}");
        installed("{\"playwright@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"playwright@market\":true}");

        var servers = plugins.servers(project);

        assertThat(servers).hasSize(1);
        var server = servers.get(0);
        assertThat(server.plugin()).isEqualTo("playwright");
        assertThat(server.key()).isEqualTo("playwright");
        assertThat(server.canonicalName()).isEqualTo("plugin_playwright_playwright");
        assertThat(server.listedName()).isEqualTo("plugin:playwright:playwright");
        assertThat(server.definition().scope()).isEqualTo(ExtensionScope.PLUGIN);
        assertThat(server.definition().command()).isEqualTo("npx");
        assertThat(server.definition().args()).containsExactly("@playwright/mcp@latest");
        assertThat(server.definition().workingDirectory()).isEqualTo(project);
    }

    @Test
    void aDisabledOrUnlistedPluginHasNoServers() throws IOException {
        var path = install("p", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"p@market\":[" + entry("user", path, "") + "],\"q@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":false}");

        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void projectSettingsOverrideUserSettingsPerPlugin() throws IOException {
        var path = install("p", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"p@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");
        enable(project.resolve(".claude/settings.local.json"), "{\"p@market\":false}");

        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void aProjectScopedInstallCountsOnlyForItsOwnProject() throws IOException {
        var path = install("p", "{\"mcpServers\":{\"s\":{\"command\":\"x\"}}}", "{}");
        installed("{\"p@market\":[" + entry("project", path, ",\"projectPath\":" + quote(project)) + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::key).containsExactly("s");
        assertThat(plugins.servers(home)).isEmpty();
        assertThat(plugins.servers(null)).isEmpty();
    }

    @Test
    void skipsOrphanedAndMissingVersionsAndPicksTheNewestOfTheRest() throws IOException {
        var orphan = install("p", "{\"old\":{\"command\":\"x\"}}", "{\"v\":1}");
        host.withFile(orphan.resolve(".orphaned_at"));
        var older = install("p", "{\"older\":{\"command\":\"x\"}}", "{\"v\":2}");
        var newer = install("p", "{\"newer\":{\"command\":\"x\"}}", "{\"v\":3}");
        var missing = config.resolve("plugins/cache/market/p/gone");
        installed("{\"p@market\":[" + entry("user", orphan, ",\"lastUpdated\":\"2030\"") + "," + entry("user", missing, ",\"lastUpdated\":\"2031\"") + ","
                + entry("user", newer, ",\"lastUpdated\":\"2026-02\"") + "," + entry("user", older, ",\"lastUpdated\":\"2026-01\"") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::key).containsExactly("newer");
    }

    @Test
    void expandsPluginVariablesAndHostEnvironmentAndReadsTheManifestAfterMcpJson() throws IOException {
        var path = install("my.plugin", "{\"mcpServers\":{\"db\":{\"command\":\"node\",\"args\":[\"${CLAUDE_PLUGIN_ROOT}/s.js\"]},\"keep\":{\"command\":\"k\"}}}", """
                {"mcpServers":{"db":{"command":"${CLAUDE_PLUGIN_ROOT}/bin","args":["${CLAUDE_PROJECT_DIR}","${CLAUDE_PLUGIN_DATA}"],"env":{"T":"${TOKEN}"}},
                 "web":{"url":"https://h/${UNSET:-mcp}"}}}
                """);
        installed("{\"my.plugin@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"my.plugin@market\":true}");

        var byKey = plugins.servers(project).stream().collect(java.util.stream.Collectors.toMap(PluginServer::key, s -> s));

        assertThat(byKey).containsOnlyKeys("db", "keep", "web");
        var db = byKey.get("db").definition();
        assertThat(db.name()).isEqualTo("plugin_my_plugin_db");
        assertThat(db.command()).isEqualTo(path + "/bin");
        assertThat(db.args()).containsExactly(project.toString(), config.resolve("plugins/data/my-plugin-market").toString());
        assertThat(db.env()).containsEntry("T", "s3cret");
        assertThat(byKey.get("web").definition().transport()).isEqualTo(McpServerDefinition.Transport.HTTP);
        assertThat(byKey.get("web").definition().url()).isEqualTo("https://h/mcp");
    }

    @Test
    void aManifestMayPointToAJsonFileAndMixInlineMaps() throws IOException {
        var path = install("p", "", "{\"mcpServers\":[\"./extra/servers.json\",{\"inline\":{\"command\":\"i\"}},\"./bundle.mcpb\"]}");
        Files.createDirectories(path.resolve("extra"));
        Files.writeString(path.resolve("extra/servers.json"), "{\"mcpServers\":{\"fromfile\":{\"command\":\"f\"}}}");
        installed("{\"p@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::key).containsExactly("fromfile", "inline");
    }

    @Test
    void malformedFilesNeverThrowAndOnlyDropThatPlugin() throws IOException {
        var broken = install("broken", "{not json", "also not json");
        var good = install("good", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"broken@market\":[" + entry("user", broken, "") + "],\"good@market\":[" + entry("user", good, "") + "],\"bad@market\":\"x\"}");
        enable(config.resolve("settings.json"), "{\"broken@market\":true,\"good@market\":true,\"bad@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::plugin).containsExactly("good");

        Files.writeString(config.resolve("plugins/installed_plugins.json"), "garbage");
        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void canonicalNamesReplaceCharactersClaudeDoesNotAllow() {
        assertThat(PluginMcpServers.canonicalName("my-plugin", "a.b c")).isEqualTo("plugin_my-plugin_a_b_c");
    }

    private Path good() throws IOException {
        var path = install("good", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"good@market\":[" + entry("user", path, "") + "]}");
        return path;
    }

    @Test
    void unusableInstallEntriesNeverMakeTheCallThrowAndOnlyDropThemselves() throws IOException {
        var good = install("good", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"good@market\":[" + entry("user", good, "") + "],"
                + "\"chars@market\":[{\"scope\":\"user\",\"installPath\":\"a?b|c\"}],"
                + "\"num@market\":[{\"scope\":\"user\",\"installPath\":5},{\"scope\":\"user\",\"installPath\":{\"x\":1}},null,\"text\",[1,2],{\"scope\":7}],"
                + "\"shape@market\":{\"scope\":\"user\"},\"null@market\":null,\"list@market\":[[],[null]]}");
        enable(config.resolve("settings.json"), "{\"good@market\":true,\"chars@market\":true,\"num@market\":true,\"shape@market\":true,\"null@market\":true,\"list@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::plugin).containsExactly("good");
    }

    @Test
    void aProjectInstallOfTheMainProjectAlsoCountsInItsTaskWorktree() throws IOException {
        var path = install("p", "{\"s\":{\"command\":\"x\",\"args\":[\"${CLAUDE_PROJECT_DIR}\"]}}", "{}");
        installed("{\"p@market\":[" + entry("project", path, ",\"projectPath\":" + quote(project)) + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");
        var worktree = Files.createDirectories(home.resolve("worktrees/task"));

        assertThat(plugins.servers(worktree)).isEmpty();
        var found = plugins.servers(worktree, project);

        assertThat(found).extracting(PluginServer::key).containsExactly("s");
        assertThat(found.get(0).definition().args()).containsExactly(worktree.toString());
        assertThat(found.get(0).definition().workingDirectory()).isEqualTo(worktree);
    }

    @Test
    void projectPathsAreComparedCaseInsensitivelyOnWindows() throws IOException {
        host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.WINDOWS, home);
        plugins = new PluginMcpServers(host, new McpConfigReader(host, new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class)));
        var path = install("p", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"p@market\":[" + entry("local", path, ",\"projectPath\":" + quote(Path.of(project.toString().toUpperCase(java.util.Locale.ROOT)))) + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).hasSize(1);
    }

    @Test
    void aHostileRepositoryCannotEnableAPluginTheUserOnlyInstalledOrDisabled() throws IOException {
        good();
        enable(project.resolve(".claude/settings.json"), "{\"good@market\":true}");
        enable(project.resolve(".claude/settings.local.json"), "{\"good@market\":true}");

        assertThat(plugins.servers(project)).isEmpty();

        enable(config.resolve("settings.json"), "{\"good@market\":false}");
        assertThat(plugins.servers(project)).isEmpty();

        enable(config.resolve("settings.json"), "{\"good@market\":true}");
        assertThat(plugins.servers(project)).hasSize(1);
    }

    @Test
    void aRepositoryCanDisableAndCanEnableAPluginInstalledForThisProject() throws IOException {
        var path = install("p", "{\"s\":{\"command\":\"x\"}}", "{}");
        installed("{\"p@market\":[" + entry("project", path, ",\"projectPath\":" + quote(project)) + "]}");
        enable(project.resolve(".claude/settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).hasSize(1);

        enable(project.resolve(".claude/settings.local.json"), "{\"p@market\":false}");
        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void managedSettingsDecideAloneAndAPluginDisabledThereStaysDisabled() throws IOException {
        var programFiles = Files.createDirectories(home.resolve("programfiles"));
        host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.WINDOWS, home).withEnv("ProgramFiles", programFiles.toString());
        plugins = new PluginMcpServers(host, new McpConfigReader(host, new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class)));
        good();
        enable(config.resolve("settings.json"), "{\"good@market\":true}");
        assertThat(plugins.servers(project)).hasSize(1);

        enable(programFiles.resolve("ClaudeCode/managed-settings.json"), "{\"good@market\":false}");
        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void aManifestPathMustStayInsideThePluginFolder() throws IOException {
        var outside = Files.writeString(home.resolve("outside.json"), "{\"evil\":{\"command\":\"x\"}}");
        var path = install("p", "", "{\"mcpServers\":[" + "\"" + outside.toString().replace("\\", "\\\\") + "\",\"../../../../../../outside.json\",\"./in.json\"]}");
        Files.writeString(path.resolve("in.json"), "{\"fine\":{\"command\":\"x\"}}");
        installed("{\"p@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::key).containsExactly("fine");
    }

    @Test
    void aSymlinkLeavingThePluginFolderIsNotFollowed() throws IOException {
        var outside = Files.writeString(home.resolve("outside.json"), "{\"evil\":{\"command\":\"x\"}}");
        var path = install("p", "", "{\"mcpServers\":[\"./link.json\"]}");
        try {
            Files.createSymbolicLink(path.resolve("link.json"), outside);
            Files.createSymbolicLink(path.resolve(".mcp.json"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here: " + e.getClass().getSimpleName());
        }
        installed("{\"p@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).isEmpty();
    }

    @Test
    void thePluginDataFolderIsCreatedInTheConfigDirectoryOnlyWhenReferenced() throws IOException {
        var path = install("p", "{\"a\":{\"command\":\"x\",\"args\":[\"${CLAUDE_PLUGIN_DATA}\"]}}", "{}");
        var plain = install("q", "{\"a\":{\"command\":\"x\"}}", "{}");
        installed("{\"p.x@m/../n\":[" + entry("user", path, "") + "],\"q@market\":[" + entry("user", plain, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p.x@m/../n\":true,\"q@market\":true}");

        assertThat(plugins.servers(project)).hasSize(2);

        assertThat(config.resolve("plugins/data/p-x-m----n")).isDirectory();
        assertThat(config.resolve("plugins/data/q-market")).doesNotExist();
    }

    @Test
    void anEntryWithNeitherCommandNorUrlIsDropped() throws IOException {
        var path = install("p", "{\"empty\":{},\"typed\":{\"type\":\"http\"},\"ok\":{\"url\":\"https://h/x\"}}", "{}");
        installed("{\"p@market\":[" + entry("user", path, "") + "]}");
        enable(config.resolve("settings.json"), "{\"p@market\":true}");

        assertThat(plugins.servers(project)).extracting(PluginServer::key).containsExactly("ok");
    }
}
