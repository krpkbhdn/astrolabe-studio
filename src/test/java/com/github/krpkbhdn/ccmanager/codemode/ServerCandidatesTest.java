package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.ServerCandidates.Candidate;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition.Transport;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerCandidatesTest {

    private static final SessionContext SESSION = new SessionContext(1, "s1");
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectCodeModeService modes = mock(ProjectCodeModeService.class);
    private final McpConfigReader reader = mock(McpConfigReader.class);
    private final McpService mcp = mock(McpService.class);
    private final PluginMcpServers plugins = mock(PluginMcpServers.class);
    private Path directory;
    private ServerCandidates candidates;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        directory = temp;
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", temp, Instant.now())));
        when(modes.directoryOf("s1")).thenReturn(Optional.empty());
        candidates = new ServerCandidates(projects, modes, reader, mcp, plugins);
    }

    private static PluginServer plugin(String plugin, String key) {
        return new PluginServer(plugin, key, new McpServerDefinition(PluginMcpServers.canonicalName(plugin, key), ExtensionScope.PLUGIN, Transport.STDIO, "npx", List.of(),
                Map.of(), "", Map.of(), null));
    }

    private static McpServerDefinition user(String name) {
        return new McpServerDefinition(name, ExtensionScope.USER, Transport.STDIO, "x", List.of(), Map.of(), "", Map.of(), null);
    }

    private void configured(String... names) {
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of(names), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
    }

    @Test
    void aPluginServerIsEligibleUnderItsKeyAndStoredUnderItsCanonicalName() {
        configured("plugin_playwright_playwright");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("playwright", "playwright")));

        var found = candidates.of(SESSION).get(0);

        assertThat(found.problem()).isNull();
        assertThat(found.name()).isEqualTo("playwright");
        assertThat(found.stored()).isEqualTo("plugin_playwright_playwright");
        assertThat(found.definition().scope()).isEqualTo(ExtensionScope.PLUGIN);
    }

    @Test
    void aClashWithAnotherConfiguredServerKeepsTheCanonicalAlias() {
        configured("plugin_a_db", "plugin_b_db", "plugin_c_files", "files");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("a", "db"), plugin("b", "db"), plugin("c", "files")));
        when(reader.definitions(directory)).thenReturn(List.of(user("files")));

        var aliases = candidates.of(SESSION).stream().map(Candidate::name).toList();

        assertThat(aliases).containsExactly("plugin_a_db", "plugin_b_db", "plugin_c_files", "files");
    }

    @Test
    void aSavedPluginServerWhoseDefinitionIsGoneIsIneligibleUnderItsStoredName() {
        configured("plugin_gone_x");

        var found = candidates.of(SESSION).get(0);

        assertThat(found.name()).isEqualTo("plugin_gone_x");
        assertThat(found.problem()).isEqualTo(ServerCandidates.NOT_FOUND);
    }

    @Test
    void twoPluginsWithTheSameCanonicalNameAreBothIneligibleAsAmbiguous() {
        configured("plugin_a_b_s");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("a.b", "s"), plugin("a_b", "s")));

        var found = candidates.of(SESSION);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).problem()).startsWith("ambiguous name");
        assertThat(found.get(0).definition()).isNull();
    }

    @Test
    void aConfiguredServerNamedLikeAPluginServerNeitherShadowsNorIsShadowed() {
        configured("plugin_p_s");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("p", "s")));
        when(reader.definitions(directory)).thenReturn(List.of(user("plugin_p_s")));

        var found = candidates.of(SESSION);

        assertThat(found).extracting(Candidate::problem).containsExactly(ServerCandidates.AMBIGUOUS);
    }

    @Test
    void aPinnedAliasSurvivesAServerThatWouldChangeIt() {
        configured("plugin_a_db", "plugin_b_db");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("a", "db"), plugin("b", "db")));

        assertThat(candidates.of(SESSION)).extracting(Candidate::name).containsExactly("plugin_a_db", "plugin_b_db");
        assertThat(candidates.of(SESSION, Map.of("plugin_a_db", "db"))).extracting(Candidate::name).containsExactly("db", "plugin_b_db");
    }

    @Test
    void aPinnedAliasMetByAnotherServerMakesBothIneligible() {
        configured("plugin_a_db", "db");
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("a", "db")));
        when(reader.definitions(directory)).thenReturn(List.of(user("db")));

        var found = candidates.of(SESSION, Map.of("plugin_a_db", "db"));

        assertThat(found).extracting(Candidate::problem).containsOnly(ServerCandidates.AMBIGUOUS);
    }

    @Test
    void pluginInstallsAreLookedUpForTheSessionWorktreeAndTheProjectRoot(@TempDir Path worktree) {
        configured("plugin_p_s");
        when(modes.directoryOf("s1")).thenReturn(Optional.of(worktree));
        when(plugins.servers(worktree, directory)).thenReturn(List.of(plugin("p", "s")));

        assertThat(candidates.of(SESSION).get(0).problem()).isNull();
    }

    @Test
    void aServerWhoseNameCouldImpersonateAnotherRequesterOrBreakARuleIsIneligible() {
        var names = List.of("mcp__cc-manager-app__open_project", "mcp__x", "has space", "a.b", "x".repeat(101), "cc-manager-code");
        configured(names.toArray(String[]::new));
        when(reader.definitions(directory)).thenReturn(names.stream().map(ServerCandidatesTest::user).toList());

        var found = candidates.of(SESSION);

        assertThat(found).allSatisfy(candidate -> assertThat(candidate.problem()).isEqualTo(ServerAliases.NAME_NOT_ALLOWED));
    }

    @Test
    void aPluginServerWhoseAliasAndCanonicalNameAreNotAllowedIsIneligible() {
        var key = "k".repeat(101);
        configured(PluginMcpServers.canonicalName("p", key));
        when(plugins.servers(directory, directory)).thenReturn(List.of(plugin("p", key)));

        assertThat(candidates.of(SESSION).get(0).problem()).isEqualTo(ServerAliases.NAME_NOT_ALLOWED);
    }

    @Test
    void ordinaryNamesStayEligible() {
        configured("jira", "my-server_2");
        when(reader.definitions(directory)).thenReturn(List.of(user("jira"), user("my-server_2")));

        assertThat(candidates.of(SESSION)).allSatisfy(candidate -> assertThat(candidate.problem()).isNull());
    }
}
