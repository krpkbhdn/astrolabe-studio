package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.plugin.InstalledPlugin;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import com.github.krpkbhdn.astrolabe.preset.apply.LocalClaudeMdNote;
import com.github.krpkbhdn.astrolabe.preset.apply.ProjectOverlaySettings;
import com.github.krpkbhdn.astrolabe.project.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Language servers of the catalog for one project: what it uses, what is installed, which Claude LSP plugin is on. */
@Service
public class ProjectToolsService {

    private record CachedPlugins(Instant readAt, List<InstalledPlugin> plugins) {
    }

    public static final String NAVIGATION_TITLE = "LSP navigation";
    private static final String NAVIGATION_RESOURCE = "/toolchain/lsp-navigation.md";
    private static final String OFFICIAL_MARKETPLACE = "claude-plugins-official";
    private static final String OFFICIAL_MARKETPLACE_SOURCE = "anthropics/claude-plugins-official";

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final TechnologyDetector detector;
    private final PluginService plugins;
    private final ClaudePluginWriter pluginWriter;
    private final LocalClaudeMdNote note;
    private final ProjectOverlaySettings overlaySettings;
    private final String navigationText;
    private final ToolchainProperties properties;
    private final GroupLspService groupLsp;
    private final Map<Path, CachedPlugins> pluginCache = new ConcurrentHashMap<>();
    private final ReentrantLock pluginWrites = new ReentrantLock();

    public ProjectToolsService(ToolCatalog catalog, ToolProbe probe, TechnologyDetector detector, PluginService plugins,
            ClaudePluginWriter pluginWriter, LocalClaudeMdNote note, ProjectOverlaySettings overlaySettings, ToolchainProperties properties,
            GroupLspService groupLsp) {
        this.catalog = catalog;
        this.probe = probe;
        this.detector = detector;
        this.plugins = plugins;
        this.pluginWriter = pluginWriter;
        this.note = note;
        this.overlaySettings = overlaySettings;
        this.properties = properties;
        this.groupLsp = groupLsp;
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
        var group = groupLsp.groupAt(projectDir);
        var found = detectIn(group.map(g -> groupLsp.memberFolders(g.id())).orElse(List.of(projectDir)), tools);
        var foundTools = tools.stream().filter(tool -> found.contains(tool.id())).toList();
        if (foundTools.isEmpty()) {
            return List.of();
        }
        var installed = foundTools.stream().anyMatch(tool -> !tool.lspPlugin().isEmpty()) ? cachedPlugins(projectDir) : List.<InstalledPlugin>of();
        return foundTools.stream().map(tool -> {
            var pluginState = group.isPresent() ? groupPluginState(group.get(), tool.lspPlugin(), projectDir) : pluginState(installed, tool.lspPlugin(), projectDir);
            var status = pluginState.serverMatters() ? probe.probe(tool.id()) : null;
            var detected = new DetectedTechnology(tool, status == null ? null : status.state(), pluginState);
            return status != null && status.state() == ToolState.FAILED ? detected.withFailure(status.detail()) : detected;
        }).toList();
    }

    private Set<String> detectIn(List<Path> folders, List<ToolDefinition> tools) {
        var found = new HashSet<String>();
        folders.forEach(folder -> found.addAll(detector.detect(folder, tools)));
        return found;
    }

    /** A group that inherits is on when any member (or the group) has the plugin on; one that overrides looks at its own settings alone. */
    private LspPluginState groupPluginState(Project group, String pluginId, Path groupDir) {
        if (pluginId.isEmpty()) {
            return LspPluginState.NOT_NEEDED;
        }
        return groupStateDirs(group, groupDir).stream().map(dir -> pluginState(cachedPlugins(dir), pluginId, dir))
                .min(Comparator.naturalOrder()).orElse(LspPluginState.NOT_INSTALLED);
    }

    private List<Path> groupStateDirs(Project group, Path groupDir) {
        var dirs = new ArrayList<Path>();
        dirs.add(groupDir);
        if (groupLsp.inherits(group.id())) {
            dirs.addAll(groupLsp.memberFolders(group.id()));
        }
        return dirs;
    }

    private boolean isEnabledForGroup(Project group, String pluginId, Path groupDir) {
        return groupStateDirs(group, groupDir).stream().anyMatch(dir -> isEnabledForProject(plugins.installed(dir), pluginId, dir));
    }

    /** True while the folder is a group that takes its LSP plugins from its members, so they cannot be edited on the group. */
    public boolean inheritsLsp(Path dir) {
        return groupLsp.groupAt(dir).map(group -> groupLsp.inherits(group.id())).orElse(false);
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

    /** The one rule for "is this LSP plugin on for the project": user level, else the app's project settings, which override what an in-project file says. */
    private boolean isEffectivelyEnabled(List<InstalledPlugin> installed, String pluginId, Path projectDir) {
        return isEnabledAtUserLevel(installed, pluginId) || isEnabledForProject(installed, pluginId, projectDir);
    }

    private boolean isEnabledForProject(List<InstalledPlugin> installed, String pluginId, Path projectDir) {
        return overlaySettings.pluginSetting(projectDir, pluginId).orElseGet(() -> installed.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.id().equals(pluginId) && isProjectOrLocalInstallation(plugin, projectDir)));
    }

    LspPluginState pluginState(List<InstalledPlugin> installed, String pluginId, Path projectDir) {
        if (pluginId.isEmpty()) {
            return LspPluginState.NOT_NEEDED;
        }
        if (isEffectivelyEnabled(installed, pluginId, projectDir)) {
            return LspPluginState.ENABLED;
        }
        var present = overlaySettings.pluginSetting(projectDir, pluginId).isPresent() || installed.stream().anyMatch(plugin -> plugin.id().equals(pluginId)
                && (isProjectOrLocalInstallation(plugin, projectDir) || plugin.isInstallationOf(pluginId, ExtensionScope.USER, null)));
        return present ? LspPluginState.DISABLED : LspPluginState.NOT_INSTALLED;
    }

    private List<ProjectToolRow> buildRows(Path projectDir, List<ToolStatus> probed) {
        var tools = catalog.all().stream().filter(tool -> tool.kind() != ToolKind.PREREQUISITE).toList();
        var group = groupLsp.groupAt(projectDir);
        var detected = detectIn(group.map(g -> groupLsp.memberFolders(g.id())).orElse(List.of(projectDir)), tools);
        var statuses = probed.stream().collect(Collectors.toMap(ToolStatus::id, Function.identity(), (a, b) -> a));
        var installedPlugins = plugins.installed(projectDir);
        var userPlugins = installedPlugins.stream()
                .filter(plugin -> plugin.enabled() && plugin.isInstallationOf(plugin.id(), ExtensionScope.USER, null))
                .map(InstalledPlugin::id).collect(Collectors.toSet());
        return tools.stream()
                .map(tool -> new ProjectToolRow(tool, statuses.getOrDefault(tool.id(), new ToolStatus(tool.id(), ToolState.MISSING, "", "")),
                        detected.contains(tool.id()), !tool.lspPlugin().isEmpty() && (group.isPresent()
                                ? isEnabledForGroup(group.get(), tool.lspPlugin(), projectDir) : isEnabledForProject(installedPlugins, tool.lspPlugin(), projectDir)),
                        userPlugins.contains(tool.lspPlugin())))
                .sorted(Comparator.comparing((ProjectToolRow row) -> row.tool().group())
                        .thenComparing(row -> !row.detected()))
                .toList();
    }

    /** Turns the tool's LSP plugin on for the project in the app's own settings, installing it for Claude first when it is not known yet; a tool without a plugin does nothing. */
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
                ensureMarketplace(pluginId);
                installForClaude(installedPlugins, pluginId);
                overlaySettings.setPlugin(projectDir, pluginId, true);
            }
            note.write(projectDir, NAVIGATION_TITLE, navigationText);
        } finally {
            pluginCache.clear();
            pluginWrites.unlock();
        }
    }

    /** A first install goes in at user scope and is switched off there, so the plugin is on only where a project's settings turn it on. */
    private void installForClaude(List<InstalledPlugin> installedPlugins, String pluginId) {
        if (installedPlugins.stream().anyMatch(plugin -> plugin.id().equals(pluginId))) {
            return;
        }
        if (plugins.install(pluginId, ExtensionScope.USER, null).needsConfirmation()) {
            throw new ExtensionException("The plugin " + pluginId + " runs a command that needs your confirmation, install it on the Plugins page");
        }
        plugins.setEnabled(pluginId, ExtensionScope.USER, null, false);
    }

    /** Turns the plugin off for the project in the app's own settings, which also wins over an in-project entry; a user-level plugin and a tool without a plugin are left alone. */
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
            if (isEnabledForProject(installed, pluginId, projectDir)) {
                overlaySettings.setPlugin(projectDir, pluginId, false);
            }
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
        return lspPlugins.stream().anyMatch(id -> isEffectivelyEnabled(installed, id, projectDir));
    }

    private void requireProjectDir(Path projectDir) {
        if (!Files.isDirectory(projectDir)) {
            throw new ExtensionException("Project folder not found: " + projectDir.getFileName());
        }
        if (inheritsLsp(projectDir)) {
            throw new ExtensionException("This group uses the LSP plugins of its projects, switch it to its own set (override) to change them");
        }
    }

    private static boolean isEnabledAtUserLevel(List<InstalledPlugin> installedPlugins, String pluginId) {
        return installedPlugins.stream()
                .anyMatch(plugin -> plugin.enabled() && plugin.isInstallationOf(pluginId, ExtensionScope.USER, null));
    }

    /** An in-project entry of the same folder (project or local scope) is only read, never written. */
    private static boolean isProjectOrLocalInstallation(InstalledPlugin plugin, Path projectDir) {
        return plugin.isInstallationOf(plugin.id(), ExtensionScope.PROJECT, projectDir) || plugin.isInstallationOf(plugin.id(), ExtensionScope.LOCAL, projectDir);
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
