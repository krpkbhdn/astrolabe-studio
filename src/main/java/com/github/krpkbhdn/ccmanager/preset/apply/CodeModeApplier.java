package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns Code Mode on for the project with exactly the preset's servers; the previous server list is replaced, never merged.
 * The applier only gets the folder, so the project is found by its path.
 */
@Component
class CodeModeApplier implements PresetComponentApplier {

    private final ProjectCodeModeService codeMode;
    private final ProjectService projects;
    private final McpService mcp;

    CodeModeApplier(ProjectCodeModeService codeMode, ProjectService projects, McpService mcp) {
        this.codeMode = codeMode;
        this.projects = projects;
        this.mcp = mcp;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.CODE_MODE;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) {
        var wanted = servers(component);
        var current = codeMode.get(projectOf(projectDir).id());
        if (current.enabled() && sameServers(current.servers(), wanted)) {
            return PlannedChange.of(component, ChangeAction.PRESENT, "Code Mode is already on with these servers");
        }
        var action = current.enabled() ? ChangeAction.UPDATE : ChangeAction.CREATE;
        return PlannedChange.of(component, action, "Turn Code Mode on for the project").withPreview(preview(current, wanted, projectDir));
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) {
        codeMode.save(projectOf(projectDir).id(), true, servers(component));
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, "Code Mode turned on");
    }

    private Project projectOf(Path projectDir) {
        var folder = projectDir.toAbsolutePath().normalize();
        return projects.list().stream().filter(project -> project.path().toAbsolutePath().normalize().equals(folder)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The folder is not a registered project, Code Mode is set per project."));
    }

    private static List<String> servers(PresetComponent component) {
        return component.payload().path("servers").valueStream().map(node -> node.asString()).toList();
    }

    private static boolean sameServers(List<String> current, List<String> wanted) {
        return new HashSet<>(current).equals(new HashSet<>(wanted));
    }

    private String preview(ProjectCodeMode current, List<String> wanted, Path projectDir) {
        var configured = mcp.servers(projectDir).stream().map(McpServerConfig::name).toList();
        var missing = wanted.stream().filter(name -> !configured.contains(name)).toList();
        var text = new StringBuilder("Code Mode: ").append(current.enabled() ? "on" : "off")
                .append(", servers now: ").append(current.servers().isEmpty() ? "none" : String.join(", ", current.servers()))
                .append("\nAfter: on, servers: ").append(String.join(", ", wanted));
        if (!missing.isEmpty()) {
            text.append("\nNot configured in this project (yet): ").append(String.join(", ", missing));
        }
        return text.toString();
    }
}
