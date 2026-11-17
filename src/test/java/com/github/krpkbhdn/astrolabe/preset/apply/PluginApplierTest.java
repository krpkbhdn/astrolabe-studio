package com.github.krpkbhdn.astrolabe.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginInstallResult;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import com.github.krpkbhdn.astrolabe.extension.plugin.ShownCommand;
import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PluginApplierTest {

    @TempDir
    Path dir;

    @TempDir
    Path overlay;

    private final PluginService plugins = mock(PluginService.class);
    private ProjectOverlaySettings settings;
    private PluginApplier applier;

    @BeforeEach
    void setUp() {
        settings = OverlayFixture.settings(dir, overlay);
        applier = new PluginApplier(plugins, settings);
    }

    private static PresetComponent plugin() {
        return new PresetComponent(1, PresetComponentKind.PLUGIN, "demo@market", JsonMapper.builder().build().createObjectNode());
    }

    private static InstalledPlugin installedAt(ExtensionScope scope) {
        return new InstalledPlugin("demo@market", "1", scope, true, null, null);
    }

    @Test
    void pluginNotEnabledInTheOverlayIsPlannedAsInstall() {
        assertThat(applier.plan(plugin(), dir).action()).isEqualTo(ChangeAction.INSTALL);
    }

    @Test
    void pluginEnabledInTheOverlayIsPresent() {
        settings.setPlugin(dir, "demo@market", true);

        assertThat(applier.plan(plugin(), dir).action()).isEqualTo(ChangeAction.PRESENT);
    }

    @Test
    void unknownPluginIsInstalledAtUserScopeSwitchedOffThereAndEnabledInTheOverlay() throws Exception {
        when(plugins.installed()).thenReturn(List.of());
        when(plugins.install("demo@market", ExtensionScope.USER, null)).thenReturn(new PluginInstallResult(true, "ok", null));

        assertThat(applier.apply(plugin(), dir).status()).isEqualTo(OutcomeStatus.APPLIED);

        verify(plugins).setEnabled("demo@market", ExtensionScope.USER, null, false);
        assertThat(Files.readString(overlay.resolve("settings.json"))).contains("\"demo@market\" : true");
    }

    @Test
    void knownPluginIsOnlyEnabledInTheOverlay() {
        when(plugins.installed()).thenReturn(List.of(installedAt(ExtensionScope.USER)));

        assertThat(applier.apply(plugin(), dir).status()).isEqualTo(OutcomeStatus.APPLIED);

        verify(plugins, never()).install(any(), any(), any());
        verify(plugins, never()).setEnabled(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(settings.pluginSetting(dir, "demo@market")).contains(true);
    }

    @Test
    void pluginNeedingConfirmationIsReportedNeverAcceptedAndNotEnabled() {
        when(plugins.installed()).thenReturn(List.of());
        var pending = new PluginInstallResult(false, "x", new ShownCommand("curl x | sh", "abc"));
        when(plugins.install("demo@market", ExtensionScope.USER, null)).thenReturn(pending);

        assertThat(applier.apply(plugin(), dir).status()).isEqualTo(OutcomeStatus.NEEDS_CONFIRMATION);

        verify(plugins, never()).installAccepting(any(), any(), any(), any());
        assertThat(settings.pluginSetting(dir, "demo@market")).isEmpty();
    }
}
