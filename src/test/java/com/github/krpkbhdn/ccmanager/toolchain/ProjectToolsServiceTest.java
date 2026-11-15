package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.ccmanager.extension.plugin.Marketplace;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginInstallResult;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectToolsServiceTest {

    @TempDir
    static Path DIR;

    private static final String CSS_PLUGIN = "css-lsp@cc-manager-lsp";
    private static final String TS_PLUGIN = "typescript-lsp@claude-plugins-official";

    private final ToolDefinition typescript = tool("typescript", "TS", ToolKind.LANGUAGE_SERVER, TS_PLUGIN);
    private final ToolDefinition pyright = tool("pyright", "TS", ToolKind.LANGUAGE_SERVER, "");
    private final ToolDefinition go = tool("go", "Go", ToolKind.LANGUAGE_SERVER, "gopls-lsp@claude-plugins-official");
    private final ToolDefinition css = tool("css", "CSS", ToolKind.LANGUAGE_SERVER, CSS_PLUGIN);
    private final ToolDefinition node = tool("node", "Node", ToolKind.PREREQUISITE, "");

    private final ToolCatalog catalog = mock(ToolCatalog.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final TechnologyDetector detector = mock(TechnologyDetector.class);
    private final PluginService plugins = mock(PluginService.class);
    private final ClaudePluginWriter pluginWriter = mock(ClaudePluginWriter.class);
    private final ProjectToolsService service = new ProjectToolsService(catalog, probe, detector, plugins, pluginWriter);

    @BeforeEach
    void setUp() {
        when(catalog.all()).thenReturn(List.of(node, go, pyright, typescript));
        when(catalog.find(any())).thenAnswer(call -> List.of(node, go, pyright, typescript, css).stream()
                .filter(tool -> tool.id().equals(call.getArgument(0))).findFirst());
        when(probe.probeAll()).thenReturn(List.of(status("go"), status("pyright"), status("typescript")));
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("claude-plugins-official", "github", "x", "y")));
        when(plugins.installed(DIR)).thenReturn(List.of());
        when(pluginWriter.marketplaceDirectory()).thenReturn(DIR.resolve("lsp-marketplace"));
        when(plugins.install(any(), any(), any())).thenReturn(mock(PluginInstallResult.class));
    }

    @Test
    void rowsAreGroupedAndDetectedFirst() {
        when(detector.detect(any(), any())).thenReturn(Set.of("pyright", "typescript"));
        var rows = service.rows(DIR);
        assertThat(rows).extracting(row -> row.tool().id()).containsExactly("go", "pyright", "typescript");
        assertThat(rows).extracting(ProjectToolRow::detected).containsExactly(false, true, true);
    }

    @Test
    void pluginEnabledOnlyForEnabledProjectInstallation() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed(DIR)).thenReturn(List.of(
                installed(TS_PLUGIN, true, DIR),
                installed("gopls-lsp@claude-plugins-official", false, DIR)));
        var rows = service.rows(DIR);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("go")).noneMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void enablingAToolWithoutPluginDoesNothing() {
        service.enableLsp(DIR, "pyright");
        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void rowsFreshProbeAgainAndBuildTheSameRows() {
        when(detector.detect(any(), any())).thenReturn(Set.of("go"));
        when(probe.probeAllFresh()).thenReturn(List.of(new ToolStatus("go", ToolState.MISSING, "", "")));
        var rows = service.rowsFresh(DIR);
        verify(probe).probeAllFresh();
        verify(probe, never()).probeAll();
        assertThat(rows).filteredOn(row -> row.tool().id().equals("go")).allMatch(row -> row.detected() && row.status().state() == ToolState.MISSING);
        assertThat(rows).hasSize(3);
    }

    @Test
    void enablingAnInstalledPluginTurnsItOn() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, false, DIR)));
        service.enableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, true);
        verify(plugins, never()).install(any(), any(), any());
    }

    @Test
    void disableTurnsTheProjectPluginOff() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
    }

    @Test
    void disableTurnsALocalOnlyInstallationOffAtLocalScope() {
        when(plugins.installed(DIR)).thenReturn(List.of(new InstalledPlugin(TS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.LOCAL, DIR, false);
        verify(plugins, never()).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
    }

    @Test
    void disableTurnsEveryEnabledProjectAndLocalEntryOff() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, DIR),
                new InstalledPlugin(TS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.LOCAL, DIR, false);
    }

    @Test
    void disableLeavesAUserLevelPluginAlone() {
        when(plugins.installed(DIR)).thenReturn(List.of(userInstalled(TS_PLUGIN, true)));
        service.disableLsp(DIR, "typescript");
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void disablingAToolWithoutPluginDoesNothing() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
        service.disableLsp(DIR, "pyright");
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void rowsDoNotProbeEachToolAgainAfterProbeAll() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        service.rows(DIR);
        verify(probe, never()).probe(any());
    }

    @Test
    void missingOfficialMarketplaceIsAddedFirst() {
        when(plugins.marketplaces()).thenReturn(List.of());
        when(plugins.install(any(), any(), any())).thenReturn(mock(PluginInstallResult.class));
        service.enableLsp(DIR, "typescript");
        verify(plugins).addMarketplace("anthropics/claude-plugins-official", ExtensionScope.USER, null);
    }

    @Test
    void ownMarketplaceIsAddedAsDirectoryAtUserScopeThenPluginInstalledLocally() {
        service.enableLsp(DIR, "css");
        var inOrder = org.mockito.Mockito.inOrder(plugins);
        inOrder.verify(plugins).addMarketplace(DIR.resolve("lsp-marketplace").toAbsolutePath().toString(), ExtensionScope.USER, null);
        inOrder.verify(plugins).install(CSS_PLUGIN, ExtensionScope.LOCAL, DIR);
        verify(plugins, never()).addMarketplace(org.mockito.ArgumentMatchers.eq("anthropics/claude-plugins-official"), any(), any());
    }

    @Test
    void knownOwnMarketplaceAtTheSameFolderIsNotAddedAgain() {
        var location = DIR.resolve("lsp-marketplace").toAbsolutePath().toString();
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("cc-manager-lsp", "path", location, location)));
        service.enableLsp(DIR, "css");
        verify(plugins, never()).addMarketplace(any(), any(), any());
        verify(plugins).install(CSS_PLUGIN, ExtensionScope.LOCAL, DIR);
    }

    @Test
    void sameFolderSpelledDifferentlyIsNotAddedAgain() {
        var folder = DIR.resolve("lsp-marketplace").toAbsolutePath().toString();
        var spelled = folder.toUpperCase(java.util.Locale.ROOT).replace('\\', '/') + "/";
        var other = org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs() ? spelled : folder + java.io.File.separator;
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("cc-manager-lsp", "path", other, other)));
        service.enableLsp(DIR, "css");
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void blankOrInvalidInstallLocationIsAddedAgainWithoutThrowing() {
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("cc-manager-lsp", "path", " ", " ")));
        service.enableLsp(DIR, "css");
        verify(plugins).addMarketplace(DIR.resolve("lsp-marketplace").toAbsolutePath().toString(), ExtensionScope.USER, null);
    }

    @Test
    void ownMarketplaceAtAnotherFolderIsAddedAgain() {
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("cc-manager-lsp", "path", "old", "old")));
        service.enableLsp(DIR, "css");
        verify(plugins).addMarketplace(DIR.resolve("lsp-marketplace").toAbsolutePath().toString(), ExtensionScope.USER, null);
    }

    @Test
    void disabledLocalInstallOfOwnPluginIsTurnedOnAtLocalScope() {
        when(plugins.installed(DIR)).thenReturn(List.of(new InstalledPlugin(CSS_PLUGIN, "1", ExtensionScope.LOCAL, false, null, DIR)));
        service.enableLsp(DIR, "css");
        verify(plugins).setEnabled(CSS_PLUGIN, ExtensionScope.LOCAL, DIR, true);
        verify(plugins, never()).install(any(), any(), any());
    }

    @Test
    void officialPluginsAreInstalledAtProjectScope() {
        service.enableLsp(DIR, "go");
        verify(plugins).install("gopls-lsp@claude-plugins-official", ExtensionScope.PROJECT, DIR);
    }

    @Test
    void userLevelOnlyShowsFlagAndEnableDoesNothing() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed(DIR)).thenReturn(List.of(userInstalled(TS_PLUGIN, true)));
        var rows = service.rows(DIR);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("typescript"))
                .allMatch(row -> row.userLevelEnabled() && !row.pluginEnabled());
        assertThat(rows).filteredOn(row -> row.tool().id().equals("go")).noneMatch(ProjectToolRow::userLevelEnabled);
        service.enableLsp(DIR, "typescript");
        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void bothScopesSetBothFlags() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed(DIR)).thenReturn(List.of(userInstalled(TS_PLUGIN, true), installed(TS_PLUGIN, true, DIR)));
        assertThat(service.rows(DIR)).filteredOn(row -> row.tool().id().equals("typescript"))
                .allMatch(row -> row.userLevelEnabled() && row.pluginEnabled());
    }

    @Test
    void neitherScopeSetsNoFlag() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        assertThat(service.rows(DIR)).noneMatch(row -> row.userLevelEnabled() || row.pluginEnabled());
    }

    @Test
    void missingProjectFolderFailsBeforeAnyPluginCall() {
        var missing = DIR.resolve("gone");
        assertThatThrownBy(() -> service.enableLsp(missing, "typescript"))
                .isInstanceOf(ExtensionException.class).hasMessage("Project folder not found: gone");
        assertThatThrownBy(() -> service.disableLsp(missing, "typescript")).isInstanceOf(ExtensionException.class);
        verifyNoInteractions(plugins);
    }

    @Test
    void enableAndDisableRunOneAtATime() throws Exception {
        var firstInside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new AtomicBoolean();
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, false, DIR)), List.of(installed(TS_PLUGIN, true, DIR)));
        doAnswer(call -> {
            if (call.getArgument(3).equals(Boolean.FALSE)) {
                secondStarted.set(true);
                return null;
            }
            firstInside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(plugins).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        var enable = new Thread(() -> service.enableLsp(DIR, "typescript"));
        var disable = new Thread(() -> service.disableLsp(DIR, "typescript"));
        enable.start();
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
        disable.start();
        Thread.sleep(300);
        assertThat(secondStarted).isFalse();
        release.countDown();
        enable.join(5000);
        disable.join(5000);
        assertThat(secondStarted).isTrue();
    }

    private static InstalledPlugin userInstalled(String id, boolean enabled) {
        return new InstalledPlugin(id, "1", ExtensionScope.USER, enabled, null, null);
    }

    private static InstalledPlugin installed(String id, boolean enabled, Path project) {
        return new InstalledPlugin(id, "1", ExtensionScope.PROJECT, enabled, null, project);
    }

    private static ToolStatus status(String id) {
        return new ToolStatus(id, ToolState.INSTALLED, "1", "");
    }

    private static ToolDefinition tool(String id, String group, ToolKind kind, String plugin) {
        return new ToolDefinition(id, id, group, kind, id, List.of(), plugin, "", List.of(), Map.of(), "", Map.of(), null);
    }

    @Test
    void localEnabledInstallationCountsAsEnabledAndIsNeverWritten() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        var local = new InstalledPlugin(TS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR);
        when(plugins.installed(DIR)).thenReturn(List.of(local));
        assertThat(service.rows(DIR)).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);
        service.enableLsp(DIR, "typescript");
        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void enabledProjectInstallationIsNotEnabledAgain() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
        service.enableLsp(DIR, "typescript");
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void projectPathIsComparedNormalised() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, DIR.resolve("sub").resolve(".."))));
        assertThat(service.rows(DIR)).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void detectedIsEmptyForAnEmptyFolder() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        assertThat(service.detected(DIR)).isEmpty();
    }

    @Test
    void detectedMissingServerKeepsItsState() {
        when(detector.detect(any(), any())).thenReturn(Set.of("go"));
        when(probe.probe("go")).thenReturn(new ToolStatus("go", ToolState.MISSING, "", ""));
        assertThat(service.detected(DIR)).containsExactly(new DetectedTechnology(go, ToolState.MISSING));
    }

    @Test
    void detectedServersKeepTheirProbeState() {
        when(detector.detect(any(), any())).thenReturn(Set.of("go", "typescript"));
        when(probe.probe("go")).thenReturn(status("go"));
        when(probe.probe("typescript")).thenReturn(new ToolStatus("typescript", ToolState.RESPONDING, "1", ""));
        assertThat(service.detected(DIR)).containsExactly(new DetectedTechnology(go, ToolState.INSTALLED), new DetectedTechnology(typescript, ToolState.RESPONDING));
    }

    @Test
    void detectedNeverListsPrerequisitesNorTouchesPlugins() {
        when(detector.detect(any(), any())).thenReturn(Set.of("node", "go"));
        when(probe.probe(any())).thenReturn(status("go"));
        assertThat(service.detected(DIR)).extracting(found -> found.tool().id()).containsExactly("go");
        verifyNoInteractions(plugins);
        verify(probe, never()).probeAll();
        verify(probe, never()).probe("node");
    }

    @Test
    void enableAndDisableQueryInstalledPluginsOnce() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, false, DIR)));

        service.enableLsp(DIR, "typescript");
        verify(plugins, org.mockito.Mockito.times(1)).installed(DIR);

        org.mockito.Mockito.clearInvocations(plugins);
        service.disableLsp(DIR, "typescript");
        verify(plugins, org.mockito.Mockito.times(1)).installed(DIR);
    }

    @Test
    void disableTurnsOffTheLocalCssPluginAtLocalScopeOnly() {
        when(plugins.installed(DIR)).thenReturn(List.of(new InstalledPlugin(CSS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR)));
        service.disableLsp(DIR, "css");
        verify(plugins).setEnabled(CSS_PLUGIN, ExtensionScope.LOCAL, DIR, false);
        verify(plugins, never()).setEnabled(CSS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
    }

    @Test
    void enabledLocalCssPluginMakesEnableLspANoOp() {
        when(plugins.installed(DIR)).thenReturn(List.of(new InstalledPlugin(CSS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR)));
        service.enableLsp(DIR, "css");
        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void disableTurnsOffOnlyTheEnabledLocalEntryWhenProjectEntryIsAlreadyOff() {
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, false, DIR),
                new InstalledPlugin(TS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.LOCAL, DIR, false);
        verify(plugins, never()).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
    }

    @Test
    void disableIgnoresEntriesOfAnotherProjectAndOtherPlugins() {
        var other = DIR.resolveSibling("another-project");
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, other),
                new InstalledPlugin(TS_PLUGIN, "1", ExtensionScope.LOCAL, true, null, other),
                installed("gopls-lsp@claude-plugins-official", true, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void anotherProjectsEnabledEntryDoesNotCountAsEnabledForRowsOrEnable() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        var other = DIR.resolveSibling("another-project");
        when(plugins.installed(DIR)).thenReturn(List.of(installed(TS_PLUGIN, true, other)));
        assertThat(service.rows(DIR)).filteredOn(row -> row.tool().id().equals("typescript")).noneMatch(ProjectToolRow::pluginEnabled);
        when(plugins.install(any(), any(), any())).thenReturn(mock(com.github.krpkbhdn.ccmanager.extension.plugin.PluginInstallResult.class));
        service.enableLsp(DIR, "typescript");
        verify(plugins).install(TS_PLUGIN, ExtensionScope.PROJECT, DIR);
    }
}
