package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlayService;
import com.github.krpkbhdn.ccmanager.preset.apply.LocalClaudeMdNote;
import com.github.krpkbhdn.ccmanager.preset.apply.ProjectOverlaySettings;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectGroupService;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GroupLspServiceTest {

    private static final String TS_PLUGIN = "typescript-lsp@claude-plugins-official";
    private static final String GO_PLUGIN = "gopls-lsp@claude-plugins-official";
    private static final String OTHER_PLUGIN = "other@market";

    @TempDir
    Path temp;

    private JdbcClient jdbc;
    private ProjectOverlayService overlays;
    private GroupLspService service;
    private Project group;
    private Project member;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO project (name, path, created_at, is_group) VALUES ('g', ?, 'now', 1)").param(temp.resolve("group").toString()).update();
        jdbc.sql("INSERT INTO project (name, path, created_at, group_id) VALUES ('m', ?, 'now', 1)").param(temp.resolve("member").toString()).update();
        group = new Project(1, "g", Files.createDirectories(temp.resolve("group")), Instant.EPOCH, null, true, null);
        member = new Project(2, "m", Files.createDirectories(temp.resolve("member")), Instant.EPOCH, null, false, 1L);
        var projects = mock(ProjectService.class);
        when(projects.list()).thenReturn(List.of(group, member));
        when(projects.findByDirectory(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> List.of(group, member).stream().filter(p -> p.path().equals(call.getArgument(0))).findFirst());
        when(projects.find(anyLong())).thenAnswer(call -> List.of(group, member).stream().filter(p -> p.id() == (long) call.getArgument(0)).findFirst());
        var catalog = mock(ToolCatalog.class);
        when(catalog.all()).thenReturn(List.of(tool("typescript", TS_PLUGIN), tool("go", GO_PLUGIN)));
        var overrides = new GroupLspOverrides(jdbc, catalog);
        overlays = new ProjectOverlayService(new AppProperties(temp.resolve("data")), projects, overrides);
        var groups = mock(ProjectGroupService.class);
        when(groups.members(1)).thenReturn(List.of(member));
        var settings = new ProjectOverlaySettings(new com.github.krpkbhdn.ccmanager.extension.ProjectOverlays(projects, overlays, mock(com.github.krpkbhdn.ccmanager.git.TaskWorktreeService.class)));
        service = new GroupLspService(overrides, catalog, overlays, groups, projects, new LocalClaudeMdNote(settings), settings);
        write(2, "{\"enabledPlugins\":{\"" + TS_PLUGIN + "\":true,\"" + OTHER_PLUGIN + "\":true}}");
    }

    private static ToolDefinition tool(String id, String plugin) {
        return new ToolDefinition(id, id, id, ToolKind.LANGUAGE_SERVER, id, List.of(), plugin, "", List.of(), Map.of(), "", Map.of(), null);
    }

    private void write(long projectId, String json) throws Exception {
        var overlay = overlays.overlayOf(projectId);
        overlay.write(overlay.settingsFile(), json);
    }

    @Test
    void aGroupInheritsTheLspPluginsOfItsMembersByDefault() {
        assertThat(service.inherits(1)).isTrue();
        assertThat(service.effectiveTools(1)).extracting(ToolDefinition::id).containsExactly("typescript");
    }

    @Test
    void overridePrefillsTheGroupWithTheInheritedLspPluginsOnly() {
        service.override(1);

        assertThat(service.inherits(1)).isFalse();
        var own = overlays.forProject(1).settings().path("enabledPlugins");
        assertThat(own.path(TS_PLUGIN).asBoolean()).isTrue();
        assertThat(own.has(OTHER_PLUGIN)).isFalse();
        assertThat(service.effectiveTools(1)).extracting(ToolDefinition::id).containsExactly("typescript");
    }

    @Test
    void anOverridingGroupWorksIndependentlyOfItsMembers() throws Exception {
        service.override(1);
        write(1, "{\"enabledPlugins\":{\"" + GO_PLUGIN + "\":true}}");

        assertThat(service.effectiveTools(1)).extracting(ToolDefinition::id).containsExactly("go");
        assertThat(overlays.forGroup(1, List.of(member)).settings().path("enabledPlugins").has(OTHER_PLUGIN)).isTrue();
    }

    @Test
    void inheritRemovesTheGroupsOwnLspEntriesAndKeepsOtherSettings() throws Exception {
        write(1, "{\"enabledPlugins\":{\"" + GO_PLUGIN + "\":true,\"" + OTHER_PLUGIN + "\":true},\"model\":\"x\"}");
        service.override(1);

        service.inherit(1);

        assertThat(service.inherits(1)).isTrue();
        var own = overlays.forProject(1).settings();
        assertThat(own.path("enabledPlugins").has(GO_PLUGIN)).isFalse();
        assertThat(own.path("enabledPlugins").has(TS_PLUGIN)).isFalse();
        assertThat(own.path("enabledPlugins").path(OTHER_PLUGIN).asBoolean()).isTrue();
        assertThat(own.path("model").asString()).isEqualTo("x");
        assertThat(service.effectiveTools(1)).extracting(ToolDefinition::id).containsExactly("typescript");
    }

    @Test
    void findsTheGroupByItsFolderAndNoOtherFolder() {
        assertThat(service.groupAt(group.path())).map(Project::id).contains(1L);
        assertThat(service.groupAt(member.path())).isEqualTo(Optional.empty());
    }

    @Test
    void deletingTheGroupDropsTheOverrideRow() {
        service.override(1);
        jdbc.sql("UPDATE project SET group_id = NULL WHERE id = 2").update();
        jdbc.sql("DELETE FROM project WHERE id = 1").update();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM group_lsp_override").query(Integer.class).single()).isZero();
    }
}
