package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.ccmanager.extension.plugin.Marketplace;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import java.nio.file.Files;
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
    private final ReentrantLock pluginWrites = new ReentrantLock();

    public ProjectToolsService(ToolCatalog catalog, ToolProbe probe, TechnologyDetector detector, PluginService plugins) {
        this.catalog = catalog;
        this.probe = probe;
        this.detector = detector;
        this.plugins = plugins;
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
                        detected.contains(tool.id()), enabledPlugins.contains(pluginOf(tool)), userPlugins.contains(pluginOf(tool))))
                .sorted(Comparator.comparing((ProjectToolRow row) -> row.tool().group())
                        .thenComparing(row -> !row.detected()))
                .toList();
    }

    /**
     * Installs the tool's LSP plugin for the project, or turns it on when it is installed but disabled; an add-on without its own
     * plugin does nothing, its base plugin is enabled on the base language server's row.
     */
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
                plugins.setEnabled(pluginId, ExtensionScope.PROJECT, projectDir, true);
            } else if (plugins.install(pluginId, ExtensionScope.PROJECT, projectDir).needsConfirmation()) {
                throw new ExtensionException("The plugin " + pluginId + " runs a command that needs your confirmation, install it on the Plugins page");
            }
        } finally {
            pluginWrites.unlock();
        }
    }

    /** Disables the plugin at the scope of each of the project's enabled entries (project and local), so it ends up off; an add-on without its own plugin does nothing, because the base plugin is shared with its language server. */
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
                .filter(plugin -> plugin.id().equals(pluginId) && isProjectInstallation(plugin, projectDir)).findFirst();
    }

    private static boolean isProjectInstallation(InstalledPlugin plugin, Path projectDir) {
        return plugin.isInstallationOf(plugin.id(), ExtensionScope.PROJECT, projectDir);
    }

    /** A local install of the same project is on for it too; it is only read, every write uses project scope. */
    private static boolean isProjectOrLocalInstallation(InstalledPlugin plugin, Path projectDir) {
        return isProjectInstallation(plugin, projectDir) || plugin.isInstallationOf(plugin.id(), ExtensionScope.LOCAL, projectDir);
    }

    private static boolean isEnabledForProject(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir));
    }

    private void ensureMarketplace(String pluginId) {
        if (!pluginId.endsWith("@" + OFFICIAL_MARKETPLACE)) {
            return;
        }
        var known = plugins.marketplaces().stream().map(Marketplace::name).anyMatch(OFFICIAL_MARKETPLACE::equals);
        if (!known) {
            plugins.addMarketplace(OFFICIAL_MARKETPLACE_SOURCE, ExtensionScope.USER, null);
        }
    }

    private ToolDefinition requireTool(String toolId) {
        return catalog.find(toolId).orElseThrow(() -> new ExtensionException("Unknown tool: " + toolId));
    }

    /** The plugin that serves the tool: its own, or its base language server's for an add-on without one. */
    private String pluginOf(ToolDefinition tool) {
        if (tool.lspPlugin().isEmpty() && !tool.baseTool().isEmpty()) {
            return catalog.find(tool.baseTool()).map(ToolDefinition::lspPlugin).orElse("");
        }
        return tool.lspPlugin();
    }
}
