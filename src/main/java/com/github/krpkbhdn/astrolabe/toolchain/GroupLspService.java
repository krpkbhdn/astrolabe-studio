package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlayService;
import com.github.krpkbhdn.astrolabe.preset.apply.LocalClaudeMdNote;
import com.github.krpkbhdn.astrolabe.preset.apply.ProjectOverlaySettings;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectGroupService;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/** LSP plugins of a group: inherited from its member projects by default, or its own set after an override that starts from the inherited one. */
@Service
public class GroupLspService {

    private static final String ENABLED_PLUGINS = "enabledPlugins";

    private final GroupLspOverrides overrides;
    private final ToolCatalog catalog;
    private final ProjectOverlayService overlays;
    private final ProjectGroupService groups;
    private final ProjectService projects;
    private final LocalClaudeMdNote note;
    private final ProjectOverlaySettings overlaySettings;
    private final String navigationText = ProjectToolsService.readNavigationText();

    public GroupLspService(GroupLspOverrides overrides, ToolCatalog catalog, ProjectOverlayService overlays, ProjectGroupService groups,
            ProjectService projects, LocalClaudeMdNote note, ProjectOverlaySettings overlaySettings) {
        this.overrides = overrides;
        this.catalog = catalog;
        this.overlays = overlays;
        this.groups = groups;
        this.projects = projects;
        this.note = note;
        this.overlaySettings = overlaySettings;
    }

    public boolean inherits(long groupId) {
        return !overrides.overrides(groupId);
    }

    /** The group whose service folder is {@code dir}, empty for any other folder. */
    public Optional<Project> groupAt(Path dir) {
        var wanted = dir.toAbsolutePath().normalize();
        return projects.list().stream().filter(project -> project.group() && project.path().toAbsolutePath().normalize().equals(wanted)).findFirst();
    }

    public List<Path> memberFolders(long groupId) {
        return groups.members(groupId).stream().map(Project::path).toList();
    }

    /** Switches to the group's own set, prefilled with what it inherits now. */
    public synchronized void override(long groupId) {
        var group = requireGroup(groupId);
        if (!inherits(groupId)) {
            return;
        }
        var inherited = effectivePluginIds(groupId);
        inherited.forEach(id -> overlaySettings.setPlugin(group.path(), id, true));
        if (!inherited.isEmpty()) {
            note.write(group.path(), ProjectToolsService.NAVIGATION_TITLE, navigationText);
        }
        overrides.set(groupId, true);
    }

    /** Back to the members' set: the group's own LSP plugin entries and navigation note are removed so nothing stale stays. */
    public synchronized void inherit(long groupId) {
        var group = requireGroup(groupId);
        overrides.set(groupId, false);
        var overlay = overlays.overlayOf(groupId);
        overlaySettings.update(overlay, settings -> {
            if (!(settings.path(ENABLED_PLUGINS) instanceof ObjectNode plugins)) {
                return null;
            }
            plugins.remove(overrides.lspPluginIds());
            if (plugins.isEmpty()) {
                settings.remove(ENABLED_PLUGINS);
            }
            return settings;
        });
        note.remove(group.path(), ProjectToolsService.NAVIGATION_TITLE);
    }

    /** The catalog tools whose LSP plugin the group's session turns on, as the overlay merge delivers it. */
    public List<ToolDefinition> effectiveTools(long groupId) {
        var ids = effectivePluginIds(groupId);
        return catalog.all().stream().filter(tool -> ids.contains(tool.lspPlugin())).toList();
    }

    private List<String> effectivePluginIds(long groupId) {
        var plugins = overlays.forGroup(groupId, groups.members(groupId)).settings().path(ENABLED_PLUGINS);
        var lsp = overrides.lspPluginIds();
        return plugins.properties().stream().filter(entry -> entry.getValue().asBoolean(false) && lsp.contains(entry.getKey()))
                .map(Map.Entry::getKey).toList();
    }

    private Project requireGroup(long groupId) {
        return projects.find(groupId).filter(Project::group).orElseThrow(() -> new ExtensionException("Not a group: " + groupId));
    }
}
