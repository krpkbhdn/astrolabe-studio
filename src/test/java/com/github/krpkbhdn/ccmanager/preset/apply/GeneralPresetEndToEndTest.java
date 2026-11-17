package com.github.krpkbhdn.ccmanager.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.extension.ClaudeCli;
import com.github.krpkbhdn.ccmanager.extension.ExtensionProperties;
import com.github.krpkbhdn.ccmanager.extension.ProjectOverlays;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlayService;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginInstallResult;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import com.github.krpkbhdn.ccmanager.preset.BundledPresetLoader;
import com.github.krpkbhdn.ccmanager.preset.PresetApplicationService;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import com.github.krpkbhdn.ccmanager.preset.ProjectPreset;
import com.github.krpkbhdn.ccmanager.preset.ProjectPresetService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real bundled General preset, real SQLite, real project folder; only the plugin and MCP CLIs are mocked. */
class GeneralPresetEndToEndTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    Path project;
    Path overlayFile;
    JdbcClient jdbc;
    ProjectPresetService presets;
    PresetApplyService service;
    ProjectPreset general;
    Set<Long> all;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectory(temp.resolve("proj"));
        overlayFile = temp.resolve("data/projects/5/instructions.md");
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("e2e.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO project (id, name, path) VALUES (5, 'p', ?)").param(project.toString()).update();
        presets = new ProjectPresetService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new BundledPresetLoader());
        presets.syncBundled();
        var plugins = mock(PluginService.class);
        when(plugins.install(anyString(), any(), any())).thenReturn(new PluginInstallResult(true, "", null));
        service = new PresetApplyService(presets, new PresetApplicationService(jdbc), List.of(new MarketplaceApplier(plugins), new PluginApplier(plugins, OverlayInstructions.settings(temp.resolve("data"), 5, project)),
                new McpServerApplier(mock(McpService.class)), new SkillApplier(OverlayInstructions.settings(temp.resolve("data"), 5, project)), new AgentApplier(OverlayInstructions.settings(temp.resolve("data"), 5, project)), new FileApplier(), OverlayInstructions.applier(temp.resolve("data"), 5, project), new SettingsApplier(OverlayInstructions.settings(temp.resolve("data"), 5, project))));
        general = presets.list().getFirst();
        all = general.components().stream().map(PresetComponent::id).collect(Collectors.toSet());
    }

    @Test
    void generalIsFirstAndAppliesToAnEmptyFolder() throws IOException {
        assertThat(general.name()).isEqualTo("General");

        var result = service.apply(5, general.id(), project, all);

        assertThat(result.outcomes()).extracting(ComponentOutcome::status).containsOnly(OutcomeStatus.APPLIED);
        var claude = Files.readString(overlayFile);
        for (var name : List.of("Duplicate code", "Clean code", "Comments", "Conventions")) {
            assertThat(claude).contains("## " + name);
        }
        assertThat(claude.split("cc-manager:begin", -1)).hasSize(5);
        assertThat(claude.split("cc-manager:end", -1)).hasSize(5);
        assertThat(temp.resolve("data/projects/5/context/.claude/skills/jscpd/SKILL.md")).isNotEmptyFile();
        assertThat(temp.resolve("data/projects/5/context/.claude/skills/dry-refactoring/SKILL.md")).isNotEmptyFile();
        assertThat(project.resolve(".jscpd.json")).isNotEmptyFile();
        assertThat(Files.readAllLines(project.resolve(".gitignore"))).containsOnlyOnce("jscpd-report/");
        assertThat(new PresetApplicationService(jdbc).applications(5)).containsEntry(general.id(), general.revision());
    }

    @Test
    void frontendInstructionsGoIntoTheOverlayAndNotIntoTheProjectFiles() throws IOException {
        var frontend = presets.list().stream().filter(p -> p.name().equals("Frontend")).findFirst().orElseThrow();
        var instructions = frontend.components().stream().filter(c -> c.kind() == PresetComponentKind.INSTRUCTION).map(PresetComponent::id).collect(Collectors.toSet());

        service.apply(5, frontend.id(), project, instructions);

        assertThat(Files.readString(overlayFile)).contains("## Frontend tools");
        assertThat(project.resolve("CLAUDE.md")).doesNotExist();
        assertThat(project.resolve("CLAUDE.local.md")).doesNotExist();
    }

    @Test
    void reapplyIsAllPresentAndByteIdentical() throws IOException {
        service.apply(5, general.id(), project, all);
        var before = Files.readString(overlayFile) + Files.readString(project.resolve(".gitignore"));

        var plan = service.plan(general.id(), project);
        var again = service.apply(5, general.id(), project, all);

        assertThat(plan).extracting(PlannedChange::action).containsOnly(ChangeAction.PRESENT);
        assertThat(again.recorded()).isTrue();
        assertThat(Files.readString(overlayFile) + Files.readString(project.resolve(".gitignore"))).isEqualTo(before);
    }

    @Test
    void editedWrittenSkillIsAConflictAndStaysUntouched() throws IOException {
        service.apply(5, general.id(), project, all);
        var skill = temp.resolve("data/projects/5/context/.claude/skills/jscpd/SKILL.md");
        Files.writeString(skill, "my own version");

        var plan = service.plan(general.id(), project);
        var result = service.apply(5, general.id(), project, all);

        assertThat(plan).filteredOn(c -> c.title().equals("jscpd")).extracting(PlannedChange::action).containsExactly(ChangeAction.CONFLICT);
        assertThat(skill).hasContent("my own version");
        assertThat(result.outcomes()).filteredOn(o -> o.status() == OutcomeStatus.APPLIED).isEmpty();
    }

    @Test
    void crlfOverlayKeepsHandWrittenContentAndLineEndings() throws IOException {
        Files.createDirectories(overlayFile.getParent());
        Files.writeString(overlayFile, "# Mine\r\n\r\nhand written line\r\n");

        service.apply(5, general.id(), project, all);

        var text = Files.readString(overlayFile);
        assertThat(text).startsWith("# Mine\r\n\r\nhand written line\r\n").contains("## Clean code").doesNotContainPattern("[^\\r]\\n");
        assertThat(service.plan(general.id(), project)).filteredOn(c -> c.kind() == PresetComponentKind.INSTRUCTION)
                .extracting(PlannedChange::action).containsOnly(ChangeAction.PRESENT);
    }

    @Test
    void parentAndAbsolutePathFileComponentsAreRejectedByTheService() {
        var id = presets.create("Evil", "").id();
        for (var path : List.of("../x", "/abs-evil.txt", "C:\\abs-evil.txt")) {
            assertThatThrownBy(() -> presets.addComponent(id, PresetComponentKind.FILE, "f", JSON.createObjectNode().put("path", path).put("content", "x")))
                    .as(path).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void escapingFilePathPlantedInTheDatabaseIsNeverWritten() {
        var id = presets.create("Evil", "").id();
        var planted = presets.addComponent(id, PresetComponentKind.FILE, "f", JSON.createObjectNode().put("path", "a.txt").put("content", "x"));
        jdbc.sql("UPDATE project_preset_component SET payload = ? WHERE id = ?").param("{\"path\":\"../x\",\"content\":\"pwn\"}").param(planted.id()).update();

        var plan = service.plan(id, project);
        service.apply(5, id, project, Set.of(planted.id()));

        assertThat(plan).extracting(PlannedChange::action).containsOnly(ChangeAction.CONFLICT);
        assertThat(temp.resolve("x")).doesNotExist();
    }

    @Test
    void everyComponentKindLeavesTheGitRepositoryByteIdentical() throws Exception {
        runGit("init", "-q");
        Files.writeString(project.resolve("README.md"), "hello");
        var before = snapshot(project);
        assertThat(before).containsKey(".git/info/exclude");

        var cli = mock(ClaudeCli.class);
        var projects = mock(ProjectService.class);
        when(projects.findByDirectory(project)).thenReturn(java.util.Optional.of(new com.github.krpkbhdn.ccmanager.project.Project(5, "p", project, java.time.Instant.now())));
        var overlayService = new ProjectOverlayService(new AppProperties(temp.resolve("data")), projects, mock(com.github.krpkbhdn.ccmanager.toolchain.GroupLspOverrides.class));
        var constructor = McpService.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var realMcp = (McpService) constructor.newInstance(cli, mock(com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader.class), null,
                new ExtensionProperties(30, 180, 90, 300, 16384), new ProjectOverlays(projects, overlayService, mock(com.github.krpkbhdn.ccmanager.git.TaskWorktreeService.class)));
        var mcp = mock(McpService.class);
        org.mockito.Mockito.doAnswer(call -> {
            realMcp.add(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            return null;
        }).when(mcp).add(anyString(), anyString(), any(), any());
        var plugins = mock(PluginService.class);
        when(plugins.install(anyString(), any(), any())).thenReturn(new PluginInstallResult(true, "", null));
        var settings = new ProjectOverlaySettings(new ProjectOverlays(projects, overlayService, mock(com.github.krpkbhdn.ccmanager.git.TaskWorktreeService.class)));
        var applier = new PresetApplyService(presets, new PresetApplicationService(jdbc), List.of(new PluginApplier(plugins, settings),
                new McpServerApplier(mcp, false), new SkillApplier(settings), new AgentApplier(settings), new InstructionApplier(settings), new SettingsApplier(settings)));

        var preset = presets.create("Mixed", "");
        presets.addComponent(preset.id(), PresetComponentKind.MCP_SERVER, "srv", JSON.createObjectNode().set("config", JSON.readTree("{\"type\":\"http\",\"url\":\"https://example.com/mcp\"}")));
        presets.addComponent(preset.id(), PresetComponentKind.SKILL, "sk", JSON.createObjectNode().set("files", JSON.createObjectNode().put("SKILL.md", "---\nname: sk\ndescription: d\n---\nbody")));
        presets.addComponent(preset.id(), PresetComponentKind.AGENT, "ag", JSON.createObjectNode().put("content", "---\nname: ag\ndescription: d\n---\nbody"));
        presets.addComponent(preset.id(), PresetComponentKind.INSTRUCTION, "Ins", JSON.createObjectNode().put("markdown", "## Ins\n\ntext"));
        presets.addComponent(preset.id(), PresetComponentKind.SETTINGS, "set", JSON.createObjectNode().set("fragment", JSON.readTree("{\"cleanupPeriodDays\":7}")));
        presets.addComponent(preset.id(), PresetComponentKind.PLUGIN, "plug@mkt", JSON.createObjectNode());
        var created = presets.list().stream().filter(p -> p.id() == preset.id()).findFirst().orElseThrow();
        var ids = created.components().stream().map(PresetComponent::id).collect(Collectors.toSet());

        var result = applier.apply(5, preset.id(), project, ids);

        assertThat(result.outcomes()).as(result.outcomes().toString()).extracting(ComponentOutcome::status).containsOnly(OutcomeStatus.APPLIED);
        assertThat(snapshot(project)).isEqualTo(before);
        var overlay = temp.resolve("data/projects/5");
        assertThat(Files.readString(overlay.resolve("mcp.json"))).contains("srv");
        assertThat(overlay.resolve("context/.claude/skills/sk/SKILL.md")).isNotEmptyFile();
        assertThat(overlay.resolve("agents/ag.md")).isNotEmptyFile();
        assertThat(Files.readString(overlay.resolve("instructions.md"))).contains("## Ins");
        var overlaySettings = Files.readString(overlay.resolve("settings.json"));
        assertThat(overlaySettings).contains("cleanupPeriodDays").contains("plug@mkt");
        var session = overlayService.forProject(5);
        assertThat(session.contextDirectories()).containsExactly(overlay.resolve("context").toAbsolutePath().normalize());
        assertThat(session.instructions()).contains("## Ins");
        assertThat(session.agents()).containsKey("ag");
        assertThat(session.mcpServers()).containsKey("srv");
        org.mockito.Mockito.verifyNoInteractions(cli);
    }

    private void runGit(String... args) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("git", "-C", project.toString()));
        command.addAll(List.of(args));
        assertThat(new ProcessBuilder(command).redirectErrorStream(true).start().waitFor()).isZero();
    }

    private static TreeMap<String, String> snapshot(Path root) throws IOException {
        var files = new TreeMap<String, String>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (var path : walk.toList()) {
                var key = root.relativize(path).toString().replace('\\', '/');
                files.put(key, Files.isDirectory(path) ? "<dir>" : java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return files;
    }
}
