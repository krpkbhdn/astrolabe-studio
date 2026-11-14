package com.github.krpkbhdn.ccmanager.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class CodeModeApplierTest {

    private static final long PROJECT_ID = 7;

    @TempDir
    Path temp;

    private ProjectCodeModeService codeMode;
    private McpService mcp;
    private CodeModeApplier applier;
    private Path dir;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = JdbcClient.create(dataSource);
        dir = temp.resolve("project");
        jdbc.sql("INSERT INTO project (id, name, path, created_at) VALUES (?, 'p', ?, 'now')").param(PROJECT_ID).param(dir.toString()).update();
        codeMode = new ProjectCodeModeService(jdbc);
        var projects = mock(ProjectService.class);
        when(projects.list()).thenReturn(List.of(new Project(PROJECT_ID, "p", dir, Instant.now())));
        mcp = mock(McpService.class);
        applier = new CodeModeApplier(codeMode, projects, mcp);
    }

    private static PresetComponent component(String payload) {
        return new PresetComponent(1, PresetComponentKind.CODE_MODE, "code-mode", JsonMapper.builder().build().readTree(payload));
    }

    private void configured(String... names) {
        when(mcp.servers(dir)).thenReturn(List.of(names).stream().map(name -> new McpServerConfig(name, ExtensionScope.PROJECT, "stdio", "x", McpApproval.NOT_REQUIRED)).toList());
    }

    @Test
    void offCodeModeIsCreatedAndAppliedWithTheServers() {
        configured("a", "b");
        var component = component("{\"servers\":[\"a\",\"b\"]}");

        var plan = applier.plan(component, dir);
        var outcome = applier.apply(component, dir);

        assertThat(plan.action()).isEqualTo(ChangeAction.CREATE);
        assertThat(plan.preview()).contains("Code Mode: off", "servers now: none", "After: on, servers: a, b").doesNotContain("Not configured");
        assertThat(outcome.status()).isEqualTo(OutcomeStatus.APPLIED);
        assertThat(codeMode.get(PROJECT_ID).enabled()).isTrue();
        assertThat(codeMode.get(PROJECT_ID).servers()).containsExactly("a", "b");
    }

    @Test
    void differentServersAreUpdatedAndReplaceTheOldOnes() {
        configured("a");
        codeMode.save(PROJECT_ID, true, List.of("x", "y"));
        var component = component("{\"servers\":[\"a\"]}");

        var plan = applier.plan(component, dir);
        applier.apply(component, dir);

        assertThat(plan.action()).isEqualTo(ChangeAction.UPDATE);
        assertThat(plan.preview()).contains("servers now: x, y");
        assertThat(codeMode.get(PROJECT_ID).servers()).containsExactly("a");
    }

    @Test
    void enabledWithTheSameServersInAnyOrderIsPresent() {
        configured("a", "b");
        codeMode.save(PROJECT_ID, true, List.of("b", "a"));

        assertThat(applier.plan(component("{\"servers\":[\"a\",\"b\"]}"), dir).action()).isEqualTo(ChangeAction.PRESENT);
    }

    @Test
    void disabledWithTheSameServersIsStillCreated() {
        configured("a");
        codeMode.save(PROJECT_ID, false, List.of("a"));

        assertThat(applier.plan(component("{\"servers\":[\"a\"]}"), dir).action()).isEqualTo(ChangeAction.CREATE);
    }

    @Test
    void unconfiguredServersAreWarnedAboutButStillApplicable() {
        configured("a");

        var plan = applier.plan(component("{\"servers\":[\"a\",\"ghost\"]}"), dir);

        assertThat(plan.action()).isEqualTo(ChangeAction.CREATE);
        assertThat(plan.preview()).contains("Not configured in this project (yet): ghost");
    }

    @Test
    void folderOfNoProjectIsRejectedSoTheServiceReportsAConflict() {
        var plan = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> applier.plan(component("{\"servers\":[\"a\"]}"), temp.resolve("elsewhere")));

        assertThat(plan).hasMessageContaining("not a registered project");
    }
}
