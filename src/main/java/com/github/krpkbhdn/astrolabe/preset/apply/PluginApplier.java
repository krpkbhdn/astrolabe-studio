package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Turns a plugin on for the project in the app's own settings; a plugin Claude does not have yet is installed once at user scope and switched off there so only this project sees it on. */
@Component
class PluginApplier implements PresetComponentApplier {

    private final PluginService plugins;
    private final ProjectOverlaySettings overlays;

    PluginApplier(PluginService plugins, ProjectOverlaySettings overlays) {
        this.plugins = plugins;
        this.overlays = overlays;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.PLUGIN;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) {
        var enabled = overlays.pluginSetting(projectDir, component.name()).orElse(false);
        return enabled ? PlannedChange.of(component, ChangeAction.PRESENT, "Plugin already enabled for this project")
                : PlannedChange.of(component, ChangeAction.INSTALL, "Enable the plugin for this project");
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) {
        var name = component.name();
        if (plugins.installed().stream().noneMatch(plugin -> plugin.id().equals(name))) {
            var result = plugins.install(name, ExtensionScope.USER, null);
            if (result.needsConfirmation()) {
                return ComponentOutcome.of(component, OutcomeStatus.NEEDS_CONFIRMATION, "The plugin runs a command that needs your confirmation, install it on the Plugins page");
            }
            plugins.setEnabled(name, ExtensionScope.USER, null, false);
        }
        overlays.setPlugin(projectDir, name, true);
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Plugin enabled for this project");
    }
}
