package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.ccmanager.extension.plugin.Marketplace;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Language servers of the catalog for one project: what it uses, what is installed, which Claude LSP plugin is on. */
@Service
public class ProjectToolsService {

    private static final String OFFICIAL_MARKETPLACE = "claude-plugins-official";
    private static final String OFFICIAL_MARKETPLACE_SOURCE = "anthropics/claude-plugins-official";

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final TechnologyDetector detector;
    private final PluginService plugins;
    private final ClaudePluginWriter pluginWriter;
    private final ReentrantLock pluginWrites = new ReentrantLock();

    public ProjectToolsService(ToolCatalog catalog, ToolProbe probe, TechnologyDetector detector, PluginService plugins,
            ClaudePluginWriter pluginWriter) {
        this.catalog = catalog;
        this.probe = probe;
        this.detector = detector;
        this.plugins = plugins;
        this.pluginWriter = pluginWriter;
    }

    /** Rows with the cached tool statuses. */
    public List<ProjectToolRow> rows(Path projectDir) {
        return buildRows(projectDir, probe.probeAll());
    }

    /** Rows after probing every tool again, for an explicit refresh. */
    public List<ProjectToolRow> rowsFresh(Path projectDir) {
        return buildRows(projectDir, probe.probeAllFresh());
    }

    /** Technologies found in the folder in catalog order with their tool states; blocking, the probe may run the binary on a cache miss, no plugin or claude calls. */
    public List<DetectedTechnology> detected(Path projectDir) {
        var tools = catalog.all().stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE).toList();
        var found = detector.detect(projectDir, tools);
        return tools.stream().filter(tool -> found.contains(tool.id()))
                .map(tool -> new DetectedTechnology(tool, probe.probe(tool.id()).state()))
                .toList();
    }

    private List<ProjectToolRow> buildRows(Path projectDir, List<ToolStatus> probed) {
        var tools = catalog.all().stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE).toList();
        var detected = detector.detect(projectDir, tools);
        var statuses = probed.stream().collect(Collectors.toMap(ToolStatus::id, Function.identity(), (a, b) -> a));
        var installedPlugins = plugins.installed(projectDir);
        var enabledPlugins = installedPlugins.stream()
                .filter(plugin -> plugin.enabled() && isProjectOrLocalInstallation(plugin, projectDir))
                .map(InstalledPlugin::id).collect(Collectors.toSet());
        var userPlugins = installedPlugins.stream()
                .filter(plugin -> plugin.enabled() && plugin.isInstallationOf(plugin.id(), ExtensionScope.USER, null))
                .map(InstalledPlugin::id).collect(Collectors.toSet());
        return tools.stream()
                .map(tool -> new ProjectToolRow(tool, statuses.getOrDefault(tool.id(), new ToolStatus(tool.id(), ToolState.MISSING, "", "")),
                        detected.contains(tool.id()), enabledPlugins.contains(tool.lspPlugin()), userPlugins.contains(tool.lspPlugin())))
                .sorted(Comparator.comparing((ProjectToolRow row) -> row.tool().group())
                        .thenComparing(row -> !row.detected()))
                .toList();
    }

    /** Installs the tool's LSP plugin for the project, or turns it on when it is installed but disabled; a tool without a plugin does nothing. */
    public void enableLsp(Path projectDir, String toolId) {
        requireProjectDir(projectDir);
        var pluginId = requireTool(toolId).lspPlugin();
        if (pluginId.isEmpty()) {
            return;
        }
        pluginWrites.lock();
        try {
            var installedPlugins = plugins.installed(projectDir);
            if (isEnabledAtUserLevel(installedPlugins, pluginId) || isEnabledForProject(installedPlugins, pluginId, projectDir)) {
                return;
            }
            ensureMarketplace(pluginId);
            var installed = findInstallation(installedPlugins, pluginId, projectDir);
            if (installed.isPresent()) {
                plugins.setEnabled(pluginId, installScope(pluginId), projectDir, true);
            } else if (plugins.install(pluginId, installScope(pluginId), projectDir).needsConfirmation()) {
                throw new ExtensionException("The plugin " + pluginId + " runs a command that needs your confirmation, install it on the Plugins page");
            }
        } finally {
            pluginWrites.unlock();
        }
    }

    /** Disables the plugin at the scope of each of the project's enabled entries (project and local), so it ends up off; a tool without a plugin does nothing. */
    public void disableLsp(Path projectDir, String toolId) {
        requireProjectDir(projectDir);
        var tool = requireTool(toolId);
        if (tool.lspPlugin().isEmpty()) {
            return;
        }
        var pluginId = tool.lspPlugin();
        pluginWrites.lock();
        try {
            plugins.installed(projectDir).stream()
                    .filter(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir))
                    .forEach(plugin -> plugins.setEnabled(pluginId, plugin.scope(), projectDir, false));
        } finally {
            pluginWrites.unlock();
        }
    }

    private static void requireProjectDir(Path projectDir) {
        if (!Files.isDirectory(projectDir)) {
            throw new ExtensionException("Project folder not found: " + projectDir.getFileName());
        }
    }

    private boolean isEnabledAtUserLevel(List<InstalledPlugin> installedPlugins, String pluginId) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.isInstallationOf(pluginId, ExtensionScope.USER, null));
    }

    private Optional<InstalledPlugin> findInstallation(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        return installedPlugins.stream()
                .filter(plugin -> plugin.isInstallationOf(pluginId, installScope(pluginId), projectDir)).findFirst();
    }

    /** A local install of the same project is on for it too; it is only read, writes use `installScope`. */
    private static boolean isProjectOrLocalInstallation(InstalledPlugin plugin, Path projectDir) {
        return plugin.isInstallationOf(plugin.id(), ExtensionScope.PROJECT, projectDir) || plugin.isInstallationOf(plugin.id(), ExtensionScope.LOCAL, projectDir);
    }

    private static boolean isEnabledForProject(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir));
    }

    /** Plugins of our own marketplace go to local scope so the shared settings never name a marketplace teammates lack. */
    private static ExtensionScope installScope(String pluginId) {
        return isOwnMarketplace(pluginId) ? ExtensionScope.LOCAL : ExtensionScope.PROJECT;
    }

    private static boolean isOwnMarketplace(String pluginId) {
        return pluginId.endsWith("@" + ClaudePluginWriter.LSP_MARKETPLACE);
    }

    private void ensureMarketplace(String pluginId) {
        if (pluginId.endsWith("@" + OFFICIAL_MARKETPLACE)) {
            addMarketplaceIfMissing(OFFICIAL_MARKETPLACE, OFFICIAL_MARKETPLACE_SOURCE);
        } else if (isOwnMarketplace(pluginId)) {
            addMarketplaceIfNotAt(ClaudePluginWriter.LSP_MARKETPLACE, pluginWriter.marketplaceDirectory());
        }
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

    private ToolDefinition requireTool(String toolId) {
        return catalog.find(toolId).orElseThrow(() -> new ExtensionException("Unknown tool: " + toolId));
    }
}
