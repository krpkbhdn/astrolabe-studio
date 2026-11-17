package com.github.krpkbhdn.ccmanager.overlay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ProjectOverlayServiceTest {

    private final ProjectService projects = mock(ProjectService.class);
    private ProjectOverlayService service;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        service = new ProjectOverlayService(new AppProperties(dataDir), projects, mock(com.github.krpkbhdn.ccmanager.toolchain.GroupLspOverrides.class));
    }

    private Project project(long id, Path path) {
        return new Project(id, "p" + id, path, Instant.now());
    }

    private void write(long id, String relative, String content) throws Exception {
        service.overlayOf(id).write(service.overlayOf(id).root().resolve(relative), content);
    }

    @Test
    void theOverlayOfAProjectLivesUnderTheDataDirByProjectId() {
        var overlay = service.overlayOf(7);

        assertThat(overlay.root()).isEqualTo(dataDir.resolve("projects").resolve("7").toAbsolutePath().normalize());
        assertThat(overlay.mcpFile()).isEqualTo(overlay.root().resolve("mcp.json"));
        assertThat(overlay.settingsFile()).isEqualTo(overlay.root().resolve("settings.json"));
        assertThat(overlay.instructionsFile()).isEqualTo(overlay.root().resolve("instructions.md"));
        assertThat(overlay.agentsDirectory()).isEqualTo(overlay.root().resolve("agents"));
        assertThat(overlay.contextDirectory()).isEqualTo(overlay.root().resolve("context"));
    }

    @Test
    void aMissingOrEmptyOverlayReadsAsEmpty() throws Exception {
        assertThat(service.forProject(1)).isSameAs(SessionOverlay.EMPTY);

        write(1, "mcp.json", "{\"mcpServers\":{}}");

        var overlay = service.forProject(1);
        assertThat(overlay.mcpServers()).isEmpty();
        assertThat(overlay.roots()).containsExactly(service.overlayOf(1).root());
    }

    @Test
    void writingKeepsFilesOwnerOnlyAndRefusesPathsOutsideTheOverlay() throws Exception {
        write(1, "agents/a.md", "x");

        assertThatThrownBy(() -> service.overlayOf(1).write(dataDir.resolve("elsewhere.json"), "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.overlayOf(1).write(service.overlayOf(1).root().resolve("..").resolve("2").resolve("x"), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        if (dataDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var file = service.overlayOf(1).agentsDirectory().resolve("a.md");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
        }
    }

    @Test
    void readsEveryPartOfTheOverlay() throws Exception {
        write(1, "mcp.json", "{\"mcpServers\":{\"tool\":{\"command\":\"x\"},\"bad\":\"text\"}}");
        write(1, "settings.json", "{\"permissions\":{\"allow\":[\"Bash(ls)\"]}}");
        write(1, "instructions.md", "<!-- cc-manager:begin a -->\n## A\nb\n<!-- cc-manager:end a -->\n");
        write(1, "agents/reviewer.md", "---\nname: reviewer\ndescription: Reviews code\ntools: Read, Grep\nmodel: sonnet\n---\nBe strict.\n");
        write(1, "agents/notes.txt", "ignored");
        write(1, "context/.claude/skills/s/SKILL.md", "skill");

        var overlay = service.forProject(1);

        assertThat(overlay.mcpServers()).containsOnlyKeys("tool");
        assertThat(overlay.settings().path("permissions").path("allow").toString()).isEqualTo("[\"Bash(ls)\"]");
        assertThat(overlay.instructions()).contains("cc-manager:begin a");
        assertThat(overlay.agents()).containsOnlyKeys("reviewer");
        var agent = overlay.agents().get("reviewer");
        assertThat(agent.description()).isEqualTo("Reviews code");
        assertThat(agent.tools()).isEqualTo("Read, Grep");
        assertThat(agent.model()).isEqualTo("sonnet");
        assertThat(agent.prompt()).isEqualTo("Be strict.");
        assertThat(overlay.contextDirectories()).containsExactly(service.overlayOf(1).contextDirectory());
        assertThat(overlay.roots()).containsExactly(service.overlayOf(1).root());
    }

    @Test
    void aContextFolderWithoutSkillsIsNotAdded() throws Exception {
        write(1, "instructions.md", "x");
        write(1, "context/readme.txt", "x");

        assertThat(service.forProject(1).contextDirectories()).isEmpty();
    }

    @Test
    void aBrokenJsonFileIsSkippedAndTheRestStillReads() throws Exception {
        write(1, "mcp.json", "{not json");
        write(1, "instructions.md", "keep");

        var overlay = service.forProject(1);

        assertThat(overlay.mcpServers()).isEmpty();
        assertThat(overlay.instructions()).isEqualTo("keep");
    }

    @Test
    void deleteRemovesTheWholeFolderAndToleratesAMissingOne() throws Exception {
        write(1, "agents/a.md", "x");

        service.delete(1);
        service.delete(1);

        assertThat(service.overlayOf(1).root()).doesNotExist();
    }

    private void makeOld(long id) throws Exception {
        Files.setLastModifiedTime(service.overlayOf(id).root(), java.nio.file.attribute.FileTime.from(Instant.now().minus(java.time.Duration.ofDays(2))));
    }

    @Test
    void sweepRemovesTheOldFoldersOfRemovedProjectsOnly() throws Exception {
        write(1, "instructions.md", "x");
        write(2, "instructions.md", "x");
        write(3, "instructions.md", "x");
        makeOld(2);
        Files.createDirectories(dataDir.resolve("projects").resolve("notes"));
        when(projects.list()).thenReturn(List.of(project(1, dataDir)));

        service.sweep();

        assertThat(service.overlayOf(1).root()).exists();
        assertThat(service.overlayOf(2).root()).doesNotExist();
        assertThat(service.overlayOf(3).root()).as("younger than the grace period").exists();
        assertThat(dataDir.resolve("projects").resolve("notes")).exists();
    }

    @Test
    void sweepDeletesNothingWhenNoProjectIsListed() throws Exception {
        write(2, "instructions.md", "x");
        makeOld(2);
        when(projects.list()).thenReturn(List.of());

        service.sweep();

        assertThat(service.overlayOf(2).root()).exists();
    }

    @Test
    void removingAProjectDeletesExactlyItsOverlayThroughTheRemovalListener() throws Exception {
        var callback = ArgumentCaptor.forClass(java.util.function.LongConsumer.class);
        verify(projects).subscribeRemoval(callback.capture());
        write(3, "instructions.md", "x");
        write(4, "instructions.md", "x");

        callback.getValue().accept(3);

        assertThat(service.overlayOf(3).root()).doesNotExist();
        assertThat(service.overlayOf(4).root()).exists();
    }

    @Test
    void aFailingProjectListDeletesNothing() throws Exception {
        write(1, "instructions.md", "x");
        when(projects.list()).thenThrow(new IllegalStateException("db"));

        service.sweep();

        assertThat(service.overlayOf(1).root()).exists();
    }

    @Test
    void aGroupMergesItsOwnOverlayThenTheMembersInOrderAndTheFirstNameWins() throws Exception {
        write(10, "mcp.json", "{\"mcpServers\":{\"shared\":{\"command\":\"group\"}}}");
        write(10, "settings.json", "{\"permissions\":{\"allow\":[\"A\"],\"defaultMode\":\"plan\"}}");
        write(10, "instructions.md", "group text");
        write(1, "mcp.json", "{\"mcpServers\":{\"shared\":{\"command\":\"one\"},\"local\":{\"command\":\"./run.sh\",\"args\":[\"./x\",\"plain\"]}}}");
        write(1, "settings.json", "{\"permissions\":{\"allow\":[\"A\",\"B\"],\"defaultMode\":\"acceptEdits\"}}");
        write(1, "instructions.md", "one text");
        write(1, "agents/dev.md", "---\ndescription: from one\n---\nOne.");
        write(2, "mcp.json", "{\"mcpServers\":{\"local\":{\"command\":\"two\"},\"extra\":{\"command\":\"two\"}}}");
        write(2, "settings.json", "{\"permissions\":{\"allow\":[\"C\"]}}");
        write(2, "instructions.md", "two text");
        write(2, "agents/dev.md", "---\ndescription: from two\n---\nTwo.");
        write(2, "context/.claude/skills/s/SKILL.md", "skill");
        var memberOne = Files.createDirectories(dataDir.resolve("one"));
        var memberTwo = Files.createDirectories(dataDir.resolve("two"));

        var merged = service.forGroup(10, List.of(project(1, memberOne), project(2, memberTwo)));

        assertThat(merged.mcpServers()).containsOnlyKeys("shared", "local", "extra");
        assertThat(merged.mcpServers().get("shared").path("command").asString()).isEqualTo("group");
        assertThat(merged.mcpServers().get("local").path("command").asString()).isEqualTo(memberOne.resolve("run.sh").toAbsolutePath().normalize().toString());
        assertThat(merged.mcpServers().get("local").path("args").get(1).asString()).isEqualTo("plain");
        assertThat(merged.settings().path("permissions").path("allow").toString()).isEqualTo("[\"A\",\"B\",\"C\"]");
        assertThat(merged.settings().path("permissions").path("defaultMode").asString()).isEqualTo("plan");
        assertThat(merged.instructions()).isEqualTo("group text\n\none text\n\ntwo text");
        assertThat(merged.agents().get("dev").description()).isEqualTo("from one");
        assertThat(merged.contextDirectories()).containsExactly(service.overlayOf(2).contextDirectory());
        assertThat(merged.roots()).containsExactly(service.overlayOf(10).root(), service.overlayOf(1).root(), service.overlayOf(2).root());
    }

    @Test
    void aGroupThatOverridesLspDropsTheMembersNavigationNoteUnlessItsOwnPluginsKeepLsp() throws Exception {
        var overrides = mock(com.github.krpkbhdn.ccmanager.toolchain.GroupLspOverrides.class);
        when(overrides.overrides(10)).thenReturn(true);
        when(overrides.lspPluginIds()).thenReturn(java.util.Set.of("lsp@m"));
        var overriding = new ProjectOverlayService(new AppProperties(dataDir), projects, overrides);
        var note = "<!-- cc-manager:begin lsp-navigation -->\n## LSP navigation\nuse it\n<!-- cc-manager:end lsp-navigation -->\n";
        write(1, "instructions.md", "mine\n\n" + note);
        write(10, "settings.json", "{\"enabledPlugins\":{\"own@m\":true}}");

        assertThat(overriding.forGroup(10, List.of(project(1, dataDir))).instructions()).contains("mine").doesNotContain("lsp-navigation");

        write(10, "settings.json", "{\"enabledPlugins\":{\"lsp@m\":true}}");

        assertThat(overriding.forGroup(10, List.of(project(1, dataDir))).instructions()).contains("lsp-navigation");
    }

    @Test
    void aGroupWithoutAnyOverlayIsEmpty() {
        assertThat(service.forGroup(10, List.of(project(1, dataDir))).isEmpty()).isTrue();
    }

    @Test
    void aGroupThatOverridesLspTakesNoLspPluginFromItsMembersButKeepsTheRest() throws Exception {
        var overrides = mock(com.github.krpkbhdn.ccmanager.toolchain.GroupLspOverrides.class);
        when(overrides.overrides(10)).thenReturn(true);
        when(overrides.lspPluginIds()).thenReturn(java.util.Set.of("lsp@m"));
        var overriding = new ProjectOverlayService(new AppProperties(dataDir), projects, overrides);
        write(10, "settings.json", "{\"enabledPlugins\":{\"own@m\":true}}");
        write(1, "settings.json", "{\"enabledPlugins\":{\"lsp@m\":true,\"other@m\":true}}");

        var merged = overriding.forGroup(10, List.of(project(1, dataDir)));

        assertThat(merged.settings().path("enabledPlugins").toString()).isEqualTo("{\"own@m\":true,\"other@m\":true}");
    }
}
