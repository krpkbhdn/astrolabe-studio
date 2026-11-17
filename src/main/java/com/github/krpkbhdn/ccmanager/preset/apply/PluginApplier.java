package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
class PluginApplier implements PresetComponentApplier {

    private final PluginService plugins;

    PluginApplier(PluginService plugins) {
        this.plugins = plugins;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.PLUGIN;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) {
        var scope = scopeOf(component);
        var present = plugins.installed().stream().anyMatch(plugin -> plugin.isInstallationOf(component.name(), scope, projectDir));
        var audience = ProjectFiles.isLocal(component) ? "only for you in this project" : "for this project";
        return present ? PlannedChange.of(component, ChangeAction.PRESENT, "Plugin already installed " + audience)
                : PlannedChange.of(component, ChangeAction.INSTALL, "Install the plugin " + audience);
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) {
        var result = plugins.install(component.name(), scopeOf(component), projectDir);
        return result.needsConfirmation()
                ? ComponentOutcome.of(component, OutcomeStatus.NEEDS_CONFIRMATION, "The plugin runs a command that needs your confirmation, install it on the Plugins page")
                : ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Plugin installed");
    }

    private static ExtensionScope scopeOf(PresetComponent component) {
        return ProjectFiles.isLocal(component) ? ExtensionScope.LOCAL : ExtensionScope.PROJECT;
    }
}
