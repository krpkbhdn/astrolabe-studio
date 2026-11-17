package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.ccmanager.extension.plugin.PluginService;
import com.github.krpkbhdn.ccmanager.git.GitLocalExclude;
import com.github.krpkbhdn.ccmanager.preset.apply.LocalClaudeMdNote;
import com.github.krpkbhdn.ccmanager.preset.apply.LocalConfigFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Language servers of the catalog for one project: what it uses, what is installed, which Claude LSP plugin is on. */
@Service
public class ProjectToolsService {

    private record CachedPlugins(Instant readAt, List<InstalledPlugin> plugins) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProjectToolsService.class);
    /** The app owns these entries and they depend on this machine, so they never go into the shared project settings. */
    private static final ExtensionScope LSP_SCOPE = ExtensionScope.LOCAL;
    private static final String NAVIGATION_TITLE = "LSP navigation";
    private static final String NAVIGATION_RESOURCE = "/toolchain/lsp-navigation.md";
    private static final String OFFICIAL_MARKETPLACE = "claude-plugins-official";
    private static final String OFFICIAL_MARKETPLACE_SOURCE = "anthropics/claude-plugins-official";

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final TechnologyDetector detector;
    private final PluginService plugins;
    private final ClaudePluginWriter pluginWriter;
    private final LocalClaudeMdNote note;
    private final GitLocalExclude localExclude;
    private final String navigationText;
    private final ToolchainProperties properties;
    private final Map<Path, CachedPlugins> pluginCache = new ConcurrentHashMap<>();
    private final ReentrantLock pluginWrites = new ReentrantLock();

    public ProjectToolsService(ToolCatalog catalog, ToolProbe probe, TechnologyDetector detector, PluginService plugins,
            ClaudePluginWriter pluginWriter, LocalClaudeMdNote note, GitLocalExclude localExclude, ToolchainProperties properties) {
        this.catalog = catalog;
        this.probe = probe;
        this.detector = detector;
        this.plugins = plugins;
        this.pluginWriter = pluginWriter;
        this.note = note;
        this.localExclude = localExclude;
        this.properties = properties;
        this.navigationText = readNavigationText();
    }

    private static String readNavigationText() {
        try (var in = ProjectToolsService.class.getResourceAsStream(NAVIGATION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled resource is missing on the classpath: " + NAVIGATION_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled resource cannot be read: " + NAVIGATION_RESOURCE, e);
        }
    }

    /** Rows with the cached tool statuses. */
    public List<ProjectToolRow> rows(Path projectDir) {
        return buildRows(projectDir, probe.probeAll());
    }

    /** Rows after probing every tool again, for an explicit refresh. */
    public List<ProjectToolRow> rowsFresh(Path projectDir) {
        return buildRows(projectDir, probe.probeAllFresh());
    }

    /**
     * Technologies found in the folder in catalog order with their plugin state and, only where the plugin is on, the server state;
     * blocking, the probe may run the binary on a cache miss and the plugin list is read through {@code claude plugin list} at most once per status TTL.
     */
    public List<DetectedTechnology> detected(Path projectDir) {
        var tools = catalog.all().stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE).toList();
        var found = detector.detect(projectDir, tools);
        var foundTools = tools.stream().filter(tool -> found.contains(tool.id())).toList();
        if (foundTools.isEmpty()) {
            return List.of();
        }
        var installed = foundTools.stream().anyMatch(tool -> !tool.lspPlugin().isEmpty()) ? cachedPlugins(projectDir) : List.<InstalledPlugin>of();
        return foundTools.stream().map(tool -> {
            var pluginState = pluginState(installed, tool.lspPlugin(), projectDir);
            return new DetectedTechnology(tool, pluginState.serverMatters() ? probe.probe(tool.id()).state() : null, pluginState);
        }).toList();
    }

    private List<InstalledPlugin> cachedPlugins(Path projectDir) {
        var key = projectDir.toAbsolutePath().normalize();
        var hit = pluginCache.get(key);
        if (hit != null && hit.readAt().plus(properties.statusTtl()).isAfter(Instant.now())) {
            return hit.plugins();
        }
        var fresh = plugins.installed(projectDir);
        pluginCache.put(key, new CachedPlugins(Instant.now(), fresh));
        return fresh;
    }

    /** The one rule for "is this LSP plugin on for the project": the CLI reports each entry's effective enabled flag, so a local false already shows the project entry as off. */
    private static boolean isEffectivelyEnabled(List<InstalledPlugin> installed, String pluginId, Path projectDir) {
        return isEnabledAtUserLevel(installed, pluginId) || isEnabledForProject(installed, pluginId, projectDir);
    }

    static LspPluginState pluginState(List<InstalledPlugin> installed, String pluginId, Path projectDir) {
        if (pluginId.isEmpty()) {
            return LspPluginState.NOT_NEEDED;
        }
        if (isEffectivelyEnabled(installed, pluginId, projectDir)) {
            return LspPluginState.ENABLED;
        }
        var present = installed.stream().anyMatch(plugin -> plugin.id().equals(pluginId)
                && (isProjectOrLocalInstallation(plugin, projectDir) || plugin.isInstallationOf(pluginId, ExtensionScope.USER, null)));
        return present ? LspPluginState.DISABLED : LspPluginState.NOT_INSTALLED;
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
            if (!isEffectivelyEnabled(installedPlugins, pluginId, projectDir)) {
                installOrEnable(installedPlugins, pluginId, projectDir);
                excludeFromGit(projectDir, LocalConfigFiles.SETTINGS);
            }
            note.write(projectDir, NAVIGATION_TITLE, navigationText);
        } finally {
            pluginCache.clear();
            pluginWrites.unlock();
        }
    }

    private void installOrEnable(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        ensureMarketplace(pluginId);
        if (findInstallation(installedPlugins, pluginId, projectDir).isPresent()) {
            plugins.setEnabled(pluginId, LSP_SCOPE, projectDir, true);
        } else if (plugins.install(pluginId, LSP_SCOPE, projectDir).needsConfirmation()) {
            throw new ExtensionException("The plugin " + pluginId + " runs a command that needs your confirmation, install it on the Plugins page");
        }
    }

    private void excludeFromGit(Path projectDir, String relativePath) {
        try {
            localExclude.ensureExcluded(projectDir, relativePath);
        } catch (IOException e) {
            LOG.warn("Could not exclude {} from git: {}", relativePath, e.getClass().getSimpleName());
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
            var installed = plugins.installed(projectDir);
            installed.stream()
                    .filter(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir))
                    .forEach(plugin -> plugins.setEnabled(pluginId, plugin.scope(), projectDir, false));
            if (!anotherLspPluginEnabled(installed, pluginId, projectDir)) {
                note.remove(projectDir, NAVIGATION_TITLE);
            }
        } finally {
            pluginCache.clear();
            pluginWrites.unlock();
        }
    }

    private boolean anotherLspPluginEnabled(List<InstalledPlugin> installed, String disabledId, Path projectDir) {
        var lspPlugins = catalog.all().stream().map(ToolDefinition::lspPlugin).filter(id -> !id.isEmpty() && !id.equals(disabledId)).collect(Collectors.toSet());
        return installed.stream().anyMatch(plugin -> plugin.enabled() && lspPlugins.contains(plugin.id())
                && (isProjectOrLocalInstallation(plugin, projectDir) || plugin.isInstallationOf(plugin.id(), ExtensionScope.USER, null)));
    }

    private static void requireProjectDir(Path projectDir) {
        if (!Files.isDirectory(projectDir)) {
            throw new ExtensionException("Project folder not found: " + projectDir.getFileName());
        }
    }

    private static boolean isEnabledAtUserLevel(List<InstalledPlugin> installedPlugins, String pluginId) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.isInstallationOf(pluginId, ExtensionScope.USER, null));
    }

    private Optional<InstalledPlugin> findInstallation(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        return installedPlugins.stream()
                .filter(plugin -> plugin.isInstallationOf(pluginId, LSP_SCOPE, projectDir)).findFirst();
    }

    /** A local install of the same project is on for it too; it is only read, writes use `installScope`. */
    private static boolean isProjectOrLocalInstallation(InstalledPlugin plugin, Path projectDir) {
        return plugin.isInstallationOf(plugin.id(), ExtensionScope.PROJECT, projectDir) || plugin.isInstallationOf(plugin.id(), ExtensionScope.LOCAL, projectDir);
    }

    private static boolean isEnabledForProject(List<InstalledPlugin> installedPlugins, String pluginId, Path projectDir) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir));
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
