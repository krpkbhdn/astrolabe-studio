package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.client.McpClient;
import com.github.krpkbhdn.ccmanager.codemode.client.McpClients;
import com.github.krpkbhdn.ccmanager.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition.Transport;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConnectionsTest {

    private static final SessionContext SESSION = new SessionContext(1, "s1");
    private static final Duration RECHECK = Duration.ofSeconds(5);

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2030-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectCodeModeService modes = mock(ProjectCodeModeService.class);
    private final McpConfigReader reader = mock(McpConfigReader.class);
    private final McpService mcp = mock(McpService.class);
    private final McpClients clients = mock(McpClients.class);
    private final McpClient first = mock(McpClient.class);
    private final McpClient second = mock(McpClient.class);
    private final ToolCallGate gate = mock(ToolCallGate.class);
    private final MutableClock clock = new MutableClock();
    private Path directory;
    private ServerConnections connections;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        directory = temp;
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", temp, Instant.now())));
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("srv"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        approval(McpApproval.APPROVED);
        definitionWith("cmd");
        org.mockito.Mockito.doReturn(first, second).when(clients).create(any());
        var properties = TestProperties.of(Duration.ofSeconds(30), 1, 1, 10, Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25))
                .toBuilder().candidateRecheckTtl(RECHECK).build();
        connections = new ServerConnections(new ServerCandidates(projects, modes, reader, mcp, org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.class)), modes, clients, properties, gate, clock);
    }

    private void approval(McpApproval approval) {
        when(mcp.servers(directory)).thenReturn(List.of(new McpServerConfig("srv", ExtensionScope.PROJECT, "stdio", "x", approval)));
    }

    private void definitionWith(String command) {
        when(reader.definitions(directory)).thenReturn(List.of(new McpServerDefinition("srv", ExtensionScope.PROJECT, Transport.STDIO, command, List.of(),
                Map.of(), "", Map.of(), null)));
    }

    @Test
    void openClientIsNotRecheckedWithinTheTtl() {
        assertThat(connections.client(SESSION, "srv")).isSameAs(first);
        approval(McpApproval.PENDING);

        assertThat(connections.client(SESSION, "srv")).isSameAs(first);
        verify(first, never()).close();
    }

    @Test
    void approvalRevokedAfterTheTtlClosesTheClientAndRefusesTheCall() {
        connections.client(SESSION, "srv");
        approval(McpApproval.PENDING);
        clock.advance(RECHECK.plusSeconds(1));

        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining(ServerCandidates.NOT_APPROVED);
        verify(first).close();
    }

    @Test
    void changedDefinitionClosesTheClientAndRefusesOnceThenReconnects() {
        connections.client(SESSION, "srv");
        definitionWith("other-cmd");
        clock.advance(RECHECK.plusSeconds(1));

        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining("definition changed");
        verify(first).close();
        assertThat(connections.client(SESSION, "srv")).isSameAs(second);
    }

    @Test
    void unchangedServerStaysOpenAndIsVerifiedAgainForAnotherTtl() {
        connections.client(SESSION, "srv");
        clock.advance(RECHECK.plusSeconds(1));

        assertThat(connections.client(SESSION, "srv")).isSameAs(first);
        approval(McpApproval.PENDING);
        assertThat(connections.client(SESSION, "srv")).isSameAs(first);
        verify(first, never()).close();
        verify(clients, times(1)).create(any());
    }

    @Test
    void closedSessionGetsNoPoolAgainEvenAfterAnEarlierPoolWasCreated() {
        connections.statuses(SESSION);
        connections.closeSession("s1");

        assertThatThrownBy(() -> connections.statuses(SESSION)).hasMessage(ServerConnections.SESSION_ENDED);
        assertThat(connections.sessionIds()).isEmpty();
    }

    @Test
    void rememberedClosedSessionsAreBoundedSoTheOldestIsForgotten() {
        connections.closeSession("first");
        for (int i = 0; i < 10_000; i++) {
            connections.closeSession("later-" + i);
        }

        assertThatThrownBy(() -> connections.statuses(new SessionContext(1, "later-9999"))).hasMessage(ServerConnections.SESSION_ENDED);
        connections.statuses(new SessionContext(1, "first"));
        assertThat(connections.sessionIds()).containsExactly("first");
    }

    @Test
    void aPluginServerIsReachedByItsAliasAndAnUpdatedInstallReconnects() {
        var plugins = mock(PluginMcpServers.class);
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("plugin_p_db"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(modes.directoryOf("s1")).thenReturn(Optional.empty());
        pluginDefinition(plugins, "v1");
        var properties = TestProperties.of(Duration.ofSeconds(30), 1, 1, 10, Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25))
                .toBuilder().candidateRecheckTtl(RECHECK).build();
        connections = new ServerConnections(new ServerCandidates(projects, modes, reader, mcp, plugins), modes, clients, properties, gate, clock);

        assertThat(connections.client(SESSION, "db")).isSameAs(first);
        assertThat(connections.statuses(SESSION)).extracting(ServerStatus::name).containsExactly("db");
        assertThatThrownBy(() -> connections.client(SESSION, "plugin_p_db")).hasMessageContaining("not a Code Mode server");

        pluginDefinition(plugins, "v2");
        clock.advance(RECHECK.plusSeconds(1));
        assertThatThrownBy(() -> connections.client(SESSION, "db")).hasMessageContaining("definition changed");
        assertThat(connections.client(SESSION, "db")).isSameAs(second);
        verify(gate).forgetServer("s1", "db");
        assertThat(connections.definitionOf(SESSION, "db").args()).containsExactly("v2");
    }

    @Test
    void aPluginServersToolsAreKeyedByItsAliasSoTheCandidateScanKeepsItsGrants() {
        var plugins = mock(PluginMcpServers.class);
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("plugin_p_db"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(modes.directoryOf("s1")).thenReturn(Optional.empty());
        pluginDefinition(plugins, "v1");
        when(first.listTools()).thenReturn(List.of(RemoteTool.annotated("plugin_p_db", "query", "d", null, Optional.empty(), false, false)));
        connections = new ServerConnections(new ServerCandidates(projects, modes, reader, mcp, plugins), modes, clients, TestProperties.of(Duration.ofSeconds(30), 1, 1, 10,
                Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25)), gate, clock);
        connections.client(SESSION, "db");

        connections.aliases(SESSION);
        connections.namesIndexJson(SESSION);

        assertThat(connections.catalog(SESSION).byServer("db")).extracting(RemoteTool::server).containsExactly("db");
        verify(gate, never()).forgetServer(any(), any());
    }

    private void pluginDefinition(PluginMcpServers plugins, String version) {
        when(plugins.servers(directory, directory)).thenReturn(List.of(new PluginServer("p", "db",
                new McpServerDefinition("plugin_p_db", ExtensionScope.PLUGIN, Transport.STDIO, "node", List.of(version), Map.of(), "", Map.of(), null))));
    }

    private static PluginServer pluginServer(String plugin) {
        return new PluginServer(plugin, "db", new McpServerDefinition("plugin_" + plugin + "_db", ExtensionScope.PLUGIN, Transport.STDIO, "node", List.of(), Map.of(), "", Map.of(), null));
    }

    @Test
    void aliasesStayFixedForTheSessionWhenAnotherServerOfTheSameKeyAppears() {
        var plugins = mock(PluginMcpServers.class);
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("plugin_a_db"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(plugins.servers(directory, directory)).thenReturn(List.of(pluginServer("a")));
        connections = new ServerConnections(new ServerCandidates(projects, modes, reader, mcp, plugins), modes, clients, TestProperties.of(Duration.ofSeconds(30), 1, 1, 10, Duration.ofMinutes(10), "img", "1m", 1,
                Duration.ofDays(1), Duration.ofSeconds(25)).toBuilder().candidateRecheckTtl(RECHECK).build(), gate, clock);
        assertThat(connections.client(SESSION, "db")).isSameAs(first);

        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("plugin_a_db", "plugin_b_db"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(plugins.servers(directory, directory)).thenReturn(List.of(pluginServer("a"), pluginServer("b")));
        clock.advance(RECHECK.plusSeconds(1));

        assertThat(connections.client(SESSION, "db")).isSameAs(first);
        assertThat(connections.statuses(SESSION)).extracting(ServerStatus::name).containsExactly("db", "plugin_b_db");
        assertThat(new ServerCandidates(projects, modes, reader, mcp, plugins).of(SESSION)).extracting(ServerCandidates.Candidate::name).containsExactly("plugin_a_db", "plugin_b_db");
    }

    @Test
    void aServerThatIsNoLongerEligibleLosesItsCatalogEntry() {
        when(first.listTools()).thenReturn(List.of(RemoteTool.annotated("srv", "t", "", null, Optional.empty(), true, false)));
        connections.client(SESSION, "srv");
        assertThat(connections.catalog(SESSION).byServer("srv")).hasSize(1);

        approval(McpApproval.PENDING);
        clock.advance(RECHECK.plusSeconds(1));

        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining(ServerCandidates.NOT_APPROVED);
        assertThat(connections.catalog(SESSION).byServer("srv")).isEmpty();
    }

    private void pluginConnections() {
        var plugins = mock(PluginMcpServers.class);
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("plugin_p_db", "srv"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(plugins.servers(directory, directory)).thenReturn(List.of(pluginServer("p")));
        connections = new ServerConnections(new ServerCandidates(projects, modes, reader, mcp, plugins), modes, clients,
                TestProperties.of(Duration.ofSeconds(30), 1, 1, 10, Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25))
                        .toBuilder().candidateRecheckTtl(RECHECK).build(), gate, clock);
    }

    @Test
    void aServerResolvesFromItsAliasItsSavedNameAndClaudeToolPrefixForms() {
        pluginConnections();

        assertThat(List.of("db", "plugin_p_db", "mcp__plugin_p_db", "mcp__plugin_p_db__query", "mcp__db__query"))
                .allSatisfy(name -> assertThat(connections.resolve(SESSION, name)).isEqualTo("db"));
        assertThat(connections.resolve(SESSION, "srv")).isEqualTo("srv");
    }

    @Test
    void anUnknownServerNamesTheAvailableAliasesWithoutHiddenCharacters() {
        pluginConnections();

        assertThatThrownBy(() -> connections.resolve(SESSION, "no‮such")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown server 'no such'. Available in Code Mode: db, srv. Use list_servers.");
    }

    @Test
    void theNamesIndexMapsEverySpellingToTheAliasAndListsTheAvailableOnes() {
        pluginConnections();

        var index = new tools.jackson.databind.json.JsonMapper().readTree(connections.namesIndexJson(SESSION));

        assertThat(index.path("names").path("db").asString()).isEqualTo("db");
        assertThat(index.path("names").path("plugin_p_db").asString()).isEqualTo("db");
        assertThat(index.path("names").path("srv").asString()).isEqualTo("srv");
        assertThat(index.path("available").asString()).isEqualTo("db, srv");
        assertThat(connections.aliases(SESSION)).containsExactly("db", "srv");
    }

    @Test
    void aServerThatChangedDefinitionOrBecameIneligibleForgetsItsGrants() {
        connections.client(SESSION, "srv");
        definitionWith("other-command");
        clock.advance(RECHECK.plusSeconds(1));
        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining("definition changed");
        verify(gate, times(1)).forgetServer("s1", "srv");

        connections.client(SESSION, "srv");
        approval(McpApproval.PENDING);
        clock.advance(RECHECK.plusSeconds(1));
        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining(ServerCandidates.NOT_APPROVED);
        verify(gate, times(2)).forgetServer("s1", "srv");
    }

    @Test
    void aServerDroppedFromTheProjectForgetsItsGrantsSoAReusedAliasStartsClean() {
        connections.client(SESSION, "srv");
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of(), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));

        assertThatThrownBy(() -> connections.client(SESSION, "srv")).hasMessageContaining("not a Code Mode server");

        verify(gate).forgetServer("s1", "srv");
    }

    @Test
    void definitionsOfGivesTheCurrentDefinitionOfUsableAliasesOnlyWithoutConnecting() {
        var found = connections.definitionsOf(SESSION, java.util.Set.of("srv", "missing"));

        assertThat(found).containsOnlyKeys("srv");
        assertThat(found.get("srv").command()).isEqualTo("cmd");
        verify(clients, never()).create(any());
    }

    @Test
    void aRestartOfAHealthyServerKeepsItsGrants() {
        connections.client(SESSION, "srv");

        connections.reset(SESSION, "srv");

        verify(gate, never()).forgetServer(any(), any());
    }

    @Test
    void manyCallsRescanTheConfigurationAtMostOncePerRecheckTtl() {
        var counting = org.mockito.Mockito.spy(new ServerCandidates(projects, modes, reader, mcp, org.mockito.Mockito.mock(PluginMcpServers.class)));
        var properties = TestProperties.of(Duration.ofSeconds(30), 1, 1, 10, Duration.ofMinutes(10), "img", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25))
                .toBuilder().candidateRecheckTtl(RECHECK).build();
        connections = new ServerConnections(counting, modes, clients, properties, gate, clock);
        connections.client(SESSION, "srv");
        org.mockito.Mockito.clearInvocations(counting);

        for (int call = 0; call < 100; call++) {
            connections.resolve(SESSION, "srv");
            connections.client(SESSION, "srv");
        }
        verify(counting, never()).of(any(), any());

        clock.advance(RECHECK.plusSeconds(1));
        for (int call = 0; call < 100; call++) {
            connections.resolve(SESSION, "srv");
            connections.client(SESSION, "srv");
        }
        verify(counting, org.mockito.Mockito.atMost(2)).of(any(), any());
    }

    @Test
    void anUnknownNameForcesARescanSoANewlyConfiguredServerIsFound() {
        assertThat(connections.resolve(SESSION, "srv")).isEqualTo("srv");
        when(modes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("srv", "late"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(reader.definitions(directory)).thenReturn(List.of(new McpServerDefinition("srv", ExtensionScope.PROJECT, Transport.STDIO, "cmd", List.of(), Map.of(), "", Map.of(), null),
                new McpServerDefinition("late", ExtensionScope.USER, Transport.STDIO, "cmd", List.of(), Map.of(), "", Map.of(), null)));

        assertThat(connections.resolve(SESSION, "late")).isEqualTo("late");
    }

    @Test
    void statusesCarrySavedNameAndScope() {
        pluginConnections();

        assertThat(connections.statuses(SESSION)).extracting(ServerStatus::name, ServerStatus::stored, ServerStatus::scope)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("db", "plugin_p_db", "plugin"), org.assertj.core.groups.Tuple.tuple("srv", "srv", "project"));
    }
}
