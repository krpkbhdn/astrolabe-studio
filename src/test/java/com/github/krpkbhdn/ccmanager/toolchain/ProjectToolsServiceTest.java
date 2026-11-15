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

    private static final String TS_PLUGIN = "typescript-lsp@claude-plugins-official";

    private final ToolDefinition typescript = tool("typescript", "TS", ToolKind.LANGUAGE_SERVER, TS_PLUGIN, "");
    private final ToolDefinition vue = tool("vue", "TS", ToolKind.ADD_ON, "", "typescript");
    private final ToolDefinition go = tool("go", "Go", ToolKind.LANGUAGE_SERVER, "gopls-lsp@claude-plugins-official", "");
    private final ToolDefinition node = tool("node", "Node", ToolKind.PREREQUISITE, "", "");

    private final ToolCatalog catalog = mock(ToolCatalog.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final TechnologyDetector detector = mock(TechnologyDetector.class);
    private final PluginService plugins = mock(PluginService.class);
    private final ProjectToolsService service = new ProjectToolsService(catalog, probe, detector, plugins);

    @BeforeEach
    void setUp() {
        when(catalog.all()).thenReturn(List.of(node, go, vue, typescript));
        when(catalog.find(any())).thenAnswer(call -> List.of(node, go, vue, typescript).stream()
                .filter(tool -> tool.id().equals(call.getArgument(0))).findFirst());
        when(probe.probeAll()).thenReturn(List.of(status("go"), status("vue"), status("typescript")));
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("claude-plugins-official", "github", "x", "y")));
        when(plugins.installed()).thenReturn(List.of());
    }

    @Test
    void rowsAreGroupedAndDetectedFirst() {
        when(detector.detect(any(), any())).thenReturn(Set.of("vue", "typescript"));
        var rows = service.rows(DIR);
        assertThat(rows).extracting(row -> row.tool().id()).containsExactly("go", "vue", "typescript");
        assertThat(rows).extracting(ProjectToolRow::detected).containsExactly(false, true, true);
    }

    @Test
    void pluginEnabledOnlyForEnabledProjectInstallation() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed()).thenReturn(List.of(
                installed(TS_PLUGIN, true, DIR),
                installed("gopls-lsp@claude-plugins-official", false, DIR)));
        var rows = service.rows(DIR);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("typescript")).allMatch(ProjectToolRow::pluginEnabled);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("vue")).allMatch(ProjectToolRow::pluginEnabled);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("go")).noneMatch(ProjectToolRow::pluginEnabled);
    }

    @Test
    void enablingAnAddOnWithoutOwnPluginDoesNothing() {
        service.enableLsp(DIR, "vue");
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
        when(plugins.installed()).thenReturn(List.of(installed(TS_PLUGIN, false, DIR)));
        service.enableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, true);
        verify(plugins, never()).install(any(), any(), any());
    }

    @Test
    void disableTurnsTheProjectPluginOff() {
        when(plugins.installed()).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
        service.disableLsp(DIR, "typescript");
        verify(plugins).setEnabled(TS_PLUGIN, ExtensionScope.PROJECT, DIR, false);
    }

    @Test
    void disablingAnAddOnWithoutOwnPluginLeavesTheBasePluginOn() {
        when(plugins.installed()).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
        service.disableLsp(DIR, "vue");
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
    void userLevelOnlyShowsFlagAndEnableDoesNothing() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed()).thenReturn(List.of(userInstalled(TS_PLUGIN, true)));
        var rows = service.rows(DIR);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("typescript"))
                .allMatch(row -> row.userLevelEnabled() && !row.pluginEnabled());
        assertThat(rows).filteredOn(row -> row.tool().id().equals("vue")).allMatch(ProjectToolRow::userLevelEnabled);
        assertThat(rows).filteredOn(row -> row.tool().id().equals("go")).noneMatch(ProjectToolRow::userLevelEnabled);
        service.enableLsp(DIR, "typescript");
        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void bothScopesSetBothFlags() {
        when(detector.detect(any(), any())).thenReturn(Set.of());
        when(plugins.installed()).thenReturn(List.of(userInstalled(TS_PLUGIN, true), installed(TS_PLUGIN, true, DIR)));
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
        when(plugins.installed()).thenReturn(List.of(installed(TS_PLUGIN, true, DIR)));
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

    private static ToolDefinition tool(String id, String group, ToolKind kind, String plugin, String base) {
        return new ToolDefinition(id, id, group, kind, id, List.of(), plugin, "", base, List.of(), Map.of(), "", Map.of(), null);
    }

    @Test
    void enableAndDisableQueryInstalledPluginsOnce() {
        when(plugins.installed()).thenReturn(List.of(installed(TS_PLUGIN, false, DIR)));

        service.enableLsp(DIR, "typescript");
        verify(plugins, org.mockito.Mockito.times(1)).installed();

        org.mockito.Mockito.clearInvocations(plugins);
        service.disableLsp(DIR, "typescript");
        verify(plugins, org.mockito.Mockito.times(1)).installed();
    }
}
