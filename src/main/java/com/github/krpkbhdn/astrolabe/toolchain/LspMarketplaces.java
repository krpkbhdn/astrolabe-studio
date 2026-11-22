package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Registers the marketplace of an LSP plugin and installs the plugin for Claude; callers hold the plugin write lock. */
class LspMarketplaces {

    private static final String OFFICIAL_MARKETPLACE = "claude-plugins-official";
    private static final String OFFICIAL_MARKETPLACE_SOURCE = "anthropics/claude-plugins-official";

    private final PluginService plugins;
    private final ClaudePluginWriter pluginWriter;

    LspMarketplaces(PluginService plugins, ClaudePluginWriter pluginWriter) {
        this.plugins = plugins;
        this.pluginWriter = pluginWriter;
    }

    void ensureMarketplace(String pluginId) {
        if (pluginId.endsWith("@" + OFFICIAL_MARKETPLACE)) {
            addMarketplaceIfMissing(OFFICIAL_MARKETPLACE, OFFICIAL_MARKETPLACE_SOURCE);
        } else if (isOwnMarketplace(pluginId)) {
            addMarketplaceIfNotAt(ClaudePluginWriter.LSP_MARKETPLACE, pluginWriter.marketplaceDirectory());
        }
    }

    /** A first install goes in at user scope and is switched off there, so the plugin is on only where a project's settings turn it on. */
    void installForClaude(List<InstalledPlugin> installedPlugins, String pluginId) {
        if (installedPlugins.stream().anyMatch(plugin -> plugin.id().equals(pluginId))) {
            return;
        }
        if (plugins.install(pluginId, ExtensionScope.USER, null).needsConfirmation()) {
            throw new ExtensionException("The plugin " + pluginId + " runs a command that needs your confirmation, install it on the Plugins page");
        }
        plugins.setEnabled(pluginId, ExtensionScope.USER, null, false);
    }

    private static boolean isOwnMarketplace(String pluginId) {
        return pluginId.endsWith("@" + ClaudePluginWriter.LSP_MARKETPLACE);
    }

    private void addMarketplaceIfMissing(String name, String source) {
        if (plugins.marketplaces().stream().noneMatch(m -> m.name().equals(name))) {
            plugins.addMarketplace(source, ExtensionScope.USER, null);
        }
    }

    /** A local marketplace is added again when it points at another folder (the CLI then repoints it). */
    private void addMarketplaceIfNotAt(String name, Path directory) {
        var known = plugins.marketplaces().stream()
                .anyMatch(m -> m.name().equals(name) && InstalledPlugin.sameDirectory(directory, pathOrNull(m.installLocation())));
        if (!known) {
            plugins.addMarketplace(directory.toAbsolutePath().toString(), ExtensionScope.USER, null);
        }
    }

    private static Path pathOrNull(String location) {
        try {
            return location == null || location.isBlank() ? null : Path.of(location);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
