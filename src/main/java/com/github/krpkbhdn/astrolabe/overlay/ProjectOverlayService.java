package com.github.krpkbhdn.astrolabe.overlay;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import com.github.krpkbhdn.astrolabe.preset.apply.ClaudeMdSections;
import com.github.krpkbhdn.astrolabe.toolchain.GroupLspOverrides;
import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolsService;
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the app's config of each project in {@code <dataDir>/projects/<projectId>/}, outside its repository, and merges it into a launch.
 * The folder is deleted with its project; a startup sweep removes leftovers older than the grace period, and never when no project is listed.
 */
@Service
@EnableConfigurationProperties(OverlayProperties.class)
public class ProjectOverlayService {

    private static final Logger log = LoggerFactory.getLogger(ProjectOverlayService.class);
    private static final String PROJECTS_DIRECTORY = "projects";

    private final Path projectsRoot;
    private final ProjectService projects;
    private final GroupLspOverrides lspOverrides;
    private final Duration sweepGrace;

    public ProjectOverlayService(AppProperties properties, ProjectService projects, GroupLspOverrides lspOverrides) {
        this(properties, projects, lspOverrides, new OverlayProperties(Duration.ofDays(1), 20_000));
    }

    @Autowired
    public ProjectOverlayService(AppProperties properties, ProjectService projects, GroupLspOverrides lspOverrides, OverlayProperties overlayProperties) {
        this.projectsRoot = properties.dataDir().resolve(PROJECTS_DIRECTORY).toAbsolutePath().normalize();
        this.projects = projects;
        this.lspOverrides = lspOverrides;
        this.sweepGrace = overlayProperties.sweepGrace();
        projects.subscribeRemoval(this::delete);
    }

    public ProjectOverlay overlayOf(long projectId) {
        return new ProjectOverlay(projectsRoot.resolve(String.valueOf(projectId)));
    }

    /** A single project's overlay; relative server paths stay as written, the session starts in the project folder. */
    public SessionOverlay forProject(long projectId) {
        return overlayOf(projectId).read(null);
    }

    /**
     * The group's own overlay first, then each member's in member order; the first of a name wins. A group that overrides LSP takes no LSP plugin
     * from its members, and no LSP navigation note either unless the group's own plugins still enable an LSP plugin.
     */
    public SessionOverlay forGroup(long groupId, List<Project> members) {
        var own = forProject(groupId);
        var layers = new ArrayList<SessionOverlay>();
        layers.add(own);
        var overridesLsp = lspOverrides.overrides(groupId);
        var lspPlugins = lspOverrides.lspPluginIds();
        var keepsNote = !overridesLsp || lspPlugins.stream().anyMatch(id -> own.settings().path("enabledPlugins").path(id).asBoolean(false));
        members.forEach(member -> {
            var layer = overlayOf(member.id()).read(member.path());
            layer = overridesLsp ? withoutPlugins(layer, lspPlugins) : layer;
            layers.add(keepsNote ? layer : withoutInstructionBlock(layer, ProjectToolsService.NAVIGATION_TITLE));
        });
        return merge(layers);
    }

    private static SessionOverlay withoutInstructionBlock(SessionOverlay layer, String title) {
        var instructions = ClaudeMdSections.remove(layer.instructions(), title);
        return new SessionOverlay(layer.mcpServers(), layer.settings(), instructions, layer.agents(), layer.contextDirectories(), layer.roots());
    }

    private static SessionOverlay withoutPlugins(SessionOverlay layer, Set<String> pluginIds) {
        if (!(layer.settings().path("enabledPlugins") instanceof ObjectNode)) {
            return layer;
        }
        var settings = layer.settings().deepCopy();
        ((ObjectNode) settings.get("enabledPlugins")).remove(pluginIds);
        return new SessionOverlay(layer.mcpServers(), settings, layer.instructions(), layer.agents(), layer.contextDirectories(), layer.roots());
    }

    static SessionOverlay merge(List<SessionOverlay> layers) {
        var servers = new LinkedHashMap<String, JsonNode>();
        var agents = new LinkedHashMap<String, OverlayAgent>();
        var instructions = new ArrayList<String>();
        var context = new ArrayList<Path>();
        var roots = new ArrayList<Path>();
        ObjectNode settings = SessionOverlay.EMPTY.settings();
        for (var layer : layers) {
            layer.mcpServers().forEach(servers::putIfAbsent);
            layer.agents().forEach(agents::putIfAbsent);
            if (!layer.instructions().isBlank()) {
                instructions.add(layer.instructions().strip());
            }
            context.addAll(layer.contextDirectories());
            roots.addAll(layer.roots());
            settings = SettingsMerge.deep(settings, layer.settings());
        }
        if (roots.isEmpty()) {
            return SessionOverlay.EMPTY;
        }
        return new SessionOverlay(servers, settings, String.join("\n\n", instructions), agents, context, roots);
    }

    /** Only a numeric folder directly inside the projects folder is ever deleted. */
    public void delete(long projectId) {
        var directory = overlayOf(projectId).root();
        try {
            if (Files.exists(directory)) {
                TreeDeleter.delete(directory);
            }
        } catch (IOException e) {
            log.warn("Could not delete the project overlay {}", directory, e);
        }
    }

    @PostConstruct
    void sweep() {
        Set<String> kept;
        try {
            kept = projects.list().stream().map(project -> String.valueOf(project.id())).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("Orphaned project overlays are not swept: {}", e.getClass().getSimpleName());
            return;
        }
        if (kept.isEmpty() || !Files.isDirectory(projectsRoot)) {
            return;
        }
        var oldest = Instant.now().minus(sweepGrace);
        try (var entries = Files.list(projectsRoot)) {
            entries.filter(entry -> entry.getFileName().toString().matches("\\d{1,18}") && !kept.contains(entry.getFileName().toString()))
                    .filter(entry -> isOlderThan(entry, oldest))
                    .forEach(entry -> delete(Long.parseLong(entry.getFileName().toString())));
        } catch (IOException e) {
            log.warn("Could not list the project overlays {}", projectsRoot, e);
        }
    }

    private static boolean isOlderThan(Path entry, Instant oldest) {
        try {
            return Files.getLastModifiedTime(entry).toInstant().isBefore(oldest);
        } catch (IOException e) {
            return false;
        }
    }
}
