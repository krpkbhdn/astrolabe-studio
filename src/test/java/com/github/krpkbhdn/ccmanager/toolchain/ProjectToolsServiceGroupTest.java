package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.ccmanager.extension.plugin.Marketplace;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginInstallResult;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import com.github.krpkbhdn.ccmanager.preset.apply.LocalClaudeMdNote;
import com.github.krpkbhdn.ccmanager.preset.apply.OverlayFixture;
import com.github.krpkbhdn.ccmanager.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectToolsServiceGroupTest {

    private static final String TS_PLUGIN = "typescript-lsp@claude-plugins-official";
    private static final String GO_PLUGIN = "gopls-lsp@claude-plugins-official";

    @TempDir
    Path temp;

    private Path groupDir;
    private Path memberA;
    private Path memberB;
    private final ToolCatalog catalog = mock(ToolCatalog.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final TechnologyDetector detector = mock(TechnologyDetector.class);
    private final PluginService plugins = mock(PluginService.class);
    private final GroupLspService groupLsp = mock(GroupLspService.class);
    private ProjectToolsService service;

    @BeforeEach
    void setUp() throws Exception {
        groupDir = Files.createDirectories(temp.resolve("group"));
        memberA = Files.createDirectories(temp.resolve("a"));
        memberB = Files.createDirectories(temp.resolve("b"));
        var overlay = OverlayFixture.settings(groupDir, Files.createDirectories(temp.resolve("overlay")));
        var typescript = tool("typescript", TS_PLUGIN);
        var go = tool("go", GO_PLUGIN);
        when(catalog.all()).thenReturn(List.of(go, typescript));
        when(catalog.find(any())).thenAnswer(call -> List.of(go, typescript).stream().filter(tool -> tool.id().equals(call.getArgument(0))).findFirst());
        when(probe.probe(any())).thenAnswer(call -> new ToolStatus(call.getArgument(0), ToolState.RESPONDING, "", ""));
        when(probe.probeAll()).thenReturn(List.of());
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("claude-plugins-official", "github", "x", "y")));
        when(plugins.install(any(), any(), any())).thenReturn(mock(PluginInstallResult.class));
        when(plugins.installed(any())).thenReturn(List.of());
        when(groupLsp.groupAt(groupDir)).thenReturn(Optional.of(new Project(9, "g", groupDir, Instant.EPOCH, null, true, null)));
        when(groupLsp.memberFolders(9)).thenReturn(List.of(memberA, memberB));
        when(detector.detect(memberA, List.of(go, typescript))).thenReturn(Set.of("typescript"));
        when(detector.detect(memberB, List.of(go, typescript))).thenReturn(Set.of("go"));
        service = new ProjectToolsService(catalog, probe, detector, plugins, mock(ClaudePluginWriter.class), new LocalClaudeMdNote(overlay), overlay,
                PROPERTIES, groupLsp);
    }

    private static final ToolchainProperties PROPERTIES = new ToolchainProperties(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(10),
            java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
            org.springframework.util.unit.DataSize.ofMegabytes(1), org.springframework.util.unit.DataSize.ofMegabytes(1), 1, java.time.Duration.ofSeconds(1), 5, 2000, 500);

    private static ToolDefinition tool(String id, String plugin) {
        return new ToolDefinition(id, id, id, ToolKind.LANGUAGE_SERVER, id, List.of(), plugin, "", List.of(), java.util.Map.of(), "", java.util.Map.of(), null);
    }

    private static InstalledPlugin enabledIn(String id, Path dir) {
        return new InstalledPlugin(id, "1", ExtensionScope.LOCAL, true, null, dir);
    }

    @Test
    void aGroupDetectsTheTechnologiesOfAllMemberFolders() {
        var detected = service.detected(groupDir);

        assertThat(detected).extracting(technology -> technology.tool().id()).containsExactly("go", "typescript");
    }

    @Test
    void aGroupThatInheritsIsOnWhenAMemberHasThePluginOn() {
        when(groupLsp.inherits(9)).thenReturn(true);
        when(plugins.installed(memberA)).thenReturn(List.of(enabledIn(TS_PLUGIN, memberA)));

        assertThat(service.detected(groupDir)).extracting(DetectedTechnology::pluginState).containsExactly(LspPluginState.NOT_INSTALLED, LspPluginState.ENABLED);
        assertThat(service.rows(groupDir)).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void aGroupThatOverridesIgnoresThePluginsOfItsMembers() {
        when(groupLsp.inherits(9)).thenReturn(false);
        when(plugins.installed(memberA)).thenReturn(List.of(enabledIn(TS_PLUGIN, memberA)));

        assertThat(service.detected(groupDir)).extracting(DetectedTechnology::pluginState).containsOnly(LspPluginState.NOT_INSTALLED);
        assertThat(service.rows(groupDir)).noneMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void enableAndDisableWorkOnTheGroupOverlayWhileOverriding() {
        when(groupLsp.inherits(9)).thenReturn(false);

        service.enableLsp(groupDir, "typescript");
        assertThat(service.rows(groupDir)).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);

        when(plugins.installed(groupDir)).thenReturn(List.of(enabledIn(TS_PLUGIN, groupDir)));
        service.disableLsp(groupDir, "typescript");
        assertThat(service.rows(groupDir)).filteredOn(row -> row.tool().id().equals("typescript")).noneMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void enableAndDisableAreRefusedWhileTheGroupInherits() {
        when(groupLsp.inherits(9)).thenReturn(true);

        assertThatThrownBy(() -> service.enableLsp(groupDir, "typescript")).isInstanceOf(ExtensionException.class).hasMessageContaining("override");
        assertThatThrownBy(() -> service.disableLsp(groupDir, "typescript")).isInstanceOf(ExtensionException.class).hasMessageContaining("override");
    }
}
