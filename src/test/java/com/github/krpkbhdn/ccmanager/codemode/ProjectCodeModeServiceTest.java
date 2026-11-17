package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalService;
import com.github.krpkbhdn.ccmanager.codemode.policy.Decision;
import com.github.krpkbhdn.ccmanager.codemode.policy.PermissionRules;
import com.github.krpkbhdn.ccmanager.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProjectCodeModeServiceTest {

    @TempDir
    Path temp;

    ProjectCodeModeService service;
    JdbcClient jdbc;
    long projectId;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        service = new ProjectCodeModeService(jdbc);
        jdbc.sql("INSERT INTO project (name, path, created_at) VALUES ('app', ?, 'now')").param(temp.toString()).update();
        projectId = jdbc.sql("SELECT id FROM project").query(Long.class).single();
    }

    @Test
    void getDefaultsToDisabledWithoutServers() {
        assertThat(service.get(projectId)).isEqualTo(new ProjectCodeMode(projectId, false, List.of(), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
    }

    @Test
    void saveStoresAndOverwritesAndNotifies() {
        var fired = new AtomicInteger();
        service.subscribe(fired::incrementAndGet);

        service.save(projectId, true, List.of(" github ", "jira"));
        var saved = service.save(projectId, true, List.of("jira"));

        assertThat(saved).isEqualTo(new ProjectCodeMode(projectId, true, List.of("jira"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        assertThat(fired).hasValue(2);
    }

    @Test
    void toolCallAccessIsAskByDefaultSavedAndKeptByTheShortSave() {
        service.save(projectId, true, List.of("jira"));
        assertThat(service.get(projectId).toolCalls()).isEqualTo(ToolCallAccess.ASK);

        service.save(projectId, true, List.of("jira"), ToolCallAccess.selected(List.of("jira")));
        service.save(projectId, true, List.of("jira", "files"));

        assertThat(service.get(projectId)).isEqualTo(new ProjectCodeMode(projectId, true, List.of("jira", "files"), ToolCallAccess.selected(List.of("jira"))));
    }

    @Test
    void toolCallAccessSurvivesARestartAndAFreshGateAllowsWithIt() {
        service.save(projectId, true, List.of("db"), ToolCallAccess.selected(List.of("db")));

        var afterRestart = new ProjectCodeModeService(jdbc);
        var gate = new ToolCallGate(Mockito.mock(ProposalService.class), PermissionRules.NONE, afterRestart);
        var wipe = RemoteTool.annotated("db", "wipe", "d", JsonMapper.builder().build().createObjectNode(), Optional.empty(), false, true);
        var definition = new McpServerDefinition("db", ExtensionScope.USER, McpServerDefinition.Transport.STDIO, "x", List.of(), Map.of(), null, Map.of(), null);

        assertThat(afterRestart.get(projectId).toolCalls()).isEqualTo(ToolCallAccess.selected(List.of("db")));
        assertThat(gate.decide(wipe, definition, JsonMapper.builder().build().createObjectNode(), new SessionContext(projectId, "later-session")).kind())
                .isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
    }

    @Test
    void migrationKeepsAnExistingAllRowAsAllAndOtherRowsAsAsk() {
        var allRow = 1L;
        var plainRow = 2L;
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("old.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).target("33").load().migrate();
        var old = JdbcClient.create(dataSource);
        old.sql("INSERT INTO project (name, path, created_at) VALUES ('a', ?, 'now')").param(temp.toString()).update();
        old.sql("INSERT INTO project (name, path, created_at) VALUES ('b', ?, 'now')").param(temp.resolve("b").toString()).update();
        old.sql("INSERT INTO project_code_mode (project_id, enabled, servers, allow_all_tool_calls) VALUES (?, 1, '[]', 1)").param(allRow).update();
        old.sql("INSERT INTO project_code_mode (project_id, enabled, servers, allow_all_tool_calls) VALUES (?, 1, '[]', 0)").param(plainRow).update();

        Flyway.configure().dataSource(dataSource).load().migrate();

        var migrated = new ProjectCodeModeService(old);
        assertThat(migrated.get(allRow).toolCalls()).isEqualTo(ToolCallAccess.ALL);
        assertThat(migrated.get(plainRow).toolCalls()).isEqualTo(ToolCallAccess.ASK);
    }

    @Test
    void saveRejectsBlankDuplicateAndTooManyServers() {
        assertThatThrownBy(() -> service.save(projectId, true, List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(projectId, true, List.of("a", "a"))).isInstanceOf(IllegalArgumentException.class);
        var many = IntStream.range(0, ProjectCodeModeService.MAX_SERVERS + 1).mapToObj(i -> "s" + i).toList();
        assertThatThrownBy(() -> service.save(projectId, true, many)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingTheProjectRemovesItsSetting() {
        service.save(projectId, true, List.of("a"));
        jdbc.sql("DELETE FROM project WHERE id = ?").param(projectId).update();

        assertThat(service.get(projectId).enabled()).isFalse();
    }
}
