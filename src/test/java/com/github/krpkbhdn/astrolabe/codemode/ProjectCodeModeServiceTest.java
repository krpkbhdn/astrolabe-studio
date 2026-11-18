package com.github.krpkbhdn.astrolabe.codemode;

import java.time.Instant;
import com.github.krpkbhdn.astrolabe.project.ProjectGroupService;
import com.github.krpkbhdn.astrolabe.project.Project;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalService;
import com.github.krpkbhdn.astrolabe.codemode.policy.Decision;
import com.github.krpkbhdn.astrolabe.codemode.policy.PermissionRules;
import com.github.krpkbhdn.astrolabe.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
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
    final ProjectGroupService groups = Mockito.mock(ProjectGroupService.class);
    long projectId;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        service = new ProjectCodeModeService(jdbc, groups);
        jdbc.sql("INSERT INTO project (name, path, created_at) VALUES ('app', ?, 'now')").param(temp.toString()).update();
        projectId = jdbc.sql("SELECT id FROM project").query(Long.class).single();
    }

    @Test
    void getDefaultsToDisabledWithoutServers() {
        assertThat(service.get(projectId)).isEqualTo(new ProjectCodeMode(projectId, false, List.of(), com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.ASK));
    }

    @Test
    void saveStoresAndOverwritesAndNotifies() {
        var fired = new AtomicInteger();
        service.subscribe(fired::incrementAndGet);

        service.save(projectId, true, List.of(" github ", "jira"));
        var saved = service.save(projectId, true, List.of("jira"));

        assertThat(saved).isEqualTo(new ProjectCodeMode(projectId, true, List.of("jira"), com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.ASK));
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

        var afterRestart = new ProjectCodeModeService(jdbc, groups);
        var gate = new ToolCallGate(Mockito.mock(ProposalService.class), PermissionRules.NONE, afterRestart);
        var wipe = RemoteTool.annotated("db", "wipe", "d", JsonMapper.builder().build().createObjectNode(), Optional.empty(), false, true);
        var definition = new McpServerDefinition("db", ExtensionScope.USER, McpServerDefinition.Transport.STDIO, "x", List.of(), Map.of(), null, Map.of(), null);

        assertThat(afterRestart.get(projectId).toolCalls()).isEqualTo(ToolCallAccess.selected(List.of("db")));
        assertThat(gate.decide(wipe, definition, JsonMapper.builder().build().createObjectNode(), new SessionContext(projectId, "later-session")).kind())
                .isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
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

    private long insertGroupOf(long memberId) {
        jdbc.sql("INSERT INTO project (name, path, created_at, is_group) VALUES ('g', ?, 'now', 1)").param(temp.resolve("g").toString()).update();
        var groupId = jdbc.sql("SELECT id FROM project WHERE is_group = 1").query(Long.class).single();
        Mockito.when(groups.members(groupId)).thenReturn(List.of(new Project(memberId, "app", temp, Instant.now())));
        return groupId;
    }

    @Test
    void aGroupWithoutARowInheritsItsMembersAndAnOverrideIsIndependent() {
        service.save(projectId, true, List.of("jira"), ToolCallAccess.selected(List.of("jira")));
        var groupId = insertGroupOf(projectId);

        assertThat(service.inherits(groupId)).isTrue();
        assertThat(service.effective(groupId)).isEqualTo(new ProjectCodeMode(groupId, true, List.of("jira"), ToolCallAccess.selected(List.of("jira"))));

        service.override(groupId);
        service.save(projectId, true, List.of("jira", "files"));
        service.save(groupId, true, List.of("jira", "extra"));

        assertThat(service.inherits(groupId)).isFalse();
        assertThat(service.effective(groupId).servers()).containsExactly("jira", "extra");
        assertThat(service.effective(projectId).servers()).containsExactly("jira", "files");

        service.inherit(groupId);

        assertThat(service.inherits(groupId)).isTrue();
        assertThat(service.effective(groupId).servers()).containsExactly("jira", "files");
    }

    @Test
    void theOverridePrefillCopiesTheInheritedAccessAndDeletingTheGroupDropsItsRow() {
        service.save(projectId, true, List.of("jira"), ToolCallAccess.ALL);
        var groupId = insertGroupOf(projectId);

        assertThat(service.override(groupId).toolCalls()).isEqualTo(ToolCallAccess.ALL);

        jdbc.sql("DELETE FROM project WHERE id = ?").param(groupId).update();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM project_code_mode WHERE project_id = ?").param(groupId).query(Integer.class).single()).isZero();
    }
}
