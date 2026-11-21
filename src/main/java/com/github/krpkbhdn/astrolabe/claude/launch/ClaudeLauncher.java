package com.github.krpkbhdn.astrolabe.claude.launch;

import com.github.krpkbhdn.astrolabe.claude.hook.CodeModeAccess;
import com.github.krpkbhdn.astrolabe.claude.hook.HookSettingsWriter;
import com.github.krpkbhdn.astrolabe.claude.hook.SessionFiles;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallation;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.astrolabe.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.astrolabe.claude.proposal.SessionTrust;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.astrolabe.extension.mcp.ProjectMcpServers;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlayService;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import com.github.krpkbhdn.astrolabe.project.GroupWorkspace;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import com.github.krpkbhdn.astrolabe.project.TrustSettingsService;
import com.github.krpkbhdn.astrolabe.project.ModelSelection;
import com.github.krpkbhdn.astrolabe.project.ProjectGroupService;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import com.github.krpkbhdn.astrolabe.team.Team;
import com.github.krpkbhdn.astrolabe.toolchain.ExecutableLookup;
import com.pty4j.PtyProcess;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts the native claude in a PTY inside the project directory with hooks injected via --settings, registers the
 * session and reports process exit to the registry. The UI attaches a terminal widget to the returned process.
 */
@Service
public class ClaudeLauncher {

    /** Project id of sessions that belong to no project; SQLite ids start at 1. */
    public static final long ASSISTANT_PROJECT_ID = 0L;
    private static final String ASSISTANT_DIRECTORY = "assistant";
    private static final int SPAWN_FAILED_EXIT_CODE = -1;
    private static final Logger log = LoggerFactory.getLogger(ClaudeLauncher.class);

    private final ProjectService projects;
    private final ClaudeInstallationService installation;
    private final HookSettingsWriter hookSettings;
    private final ClaudePluginWriter plugin;
    private final SessionRegistry sessions;
    private final Path dataDir;
    private final ProjectGroupService groups;
    private final GroupWorkspace groupWorkspace;
    private final ProjectMcpServers projectMcpServers;
    private final AssistantMcpServer assistantMcp;
    private final WorkDirectories workDirectories;
    private final ProjectCodeModeService codeModes;
    private final McpConfigReader mcpConfigs;
    private final ExecutableLookup executableLookup;
    private final SessionTrust sessionTrust;
    private final TrustSettingsService trustSettings;
    private final ProjectOverlayService overlays;

    public ClaudeLauncher(ProjectService projects, ClaudeInstallationService installation,
            HookSettingsWriter hookSettings, ClaudePluginWriter plugin, SessionRegistry sessions, AppProperties properties,
            ProjectGroupService groups, GroupWorkspace groupWorkspace, ProjectMcpServers projectMcpServers,
            AssistantMcpServer assistantMcp, WorkDirectories workDirectories,
            ProjectCodeModeService codeModes, McpConfigReader mcpConfigs, ExecutableLookup executableLookup, SessionTrust sessionTrust,
            TrustSettingsService trustSettings, ProjectOverlayService overlays) {
        this.overlays = overlays;
        this.trustSettings = trustSettings;
        this.sessionTrust = sessionTrust;
        this.executableLookup = executableLookup;
        this.codeModes = codeModes;
        this.mcpConfigs = mcpConfigs;
        this.assistantMcp = assistantMcp;
        this.workDirectories = workDirectories;
        this.dataDir = properties.dataDir();
        this.groups = groups;
        this.groupWorkspace = groupWorkspace;
        this.projectMcpServers = projectMcpServers;
        this.projects = projects;
        this.installation = installation;
        this.hookSettings = hookSettings;
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /** @throws IllegalStateException with a user-readable message (Claude missing, project gone, spawn failed) */
    public LaunchedSession launch(long projectId, LaunchMode mode) {
        return start(projectId, ClaudeCommand.label(mode), null,
                (files, exe, selection) -> ClaudeCommand.launchArguments(exe, files, plugin.directory(), mode, selection));
    }

    /** Like {@link #launch(long, LaunchMode)} but with [team] as agents, so the team also applies to a new, continued or resumed conversation. */
    public LaunchedSession launch(long projectId, LaunchMode mode, Team team) {
        return start(projectId, ClaudeCommand.label(mode, team), team,
                (files, exe, selection) -> ClaudeCommand.launchArguments(exe, files, plugin.directory(), mode, selection, team));
    }

    /** Like {@link #launch(long, LaunchMode, Team)} in [directory], the project folder or one of its git worktrees; the session still belongs to the project. */
    public LaunchedSession launchIn(long projectId, Path directory, LaunchMode mode, Team team) {
        return startIn(projectId, directory, ClaudeCommand.label(mode, team), team, (files, exe, selection) -> team == null
                ? ClaudeCommand.launchArguments(exe, files, plugin.directory(), mode, selection)
                : ClaudeCommand.launchArguments(exe, files, plugin.directory(), mode, selection, team));
    }

    /** A task session in [directory] (project folder or git worktree); with a [team] as {@link #launchTeam}, without as {@link #launchTask}. */
    public LaunchedSession launchTaskIn(long projectId, Path directory, Team team, String task) {
        if (team == null) {
            return startIn(projectId, directory, ClaudeCommand.label(LaunchMode.NEW), null,
                    (files, exe, selection) -> ClaudeCommand.taskArguments(exe, files, plugin.directory(), selection, task));
        }
        return startIn(projectId, directory, ClaudeCommand.label(LaunchMode.NEW, team), team,
                (files, exe, selection) -> ClaudeCommand.teamArguments(exe, files, plugin.directory(), team, task));
    }

    /** Resumes a stored conversation in [cwd] (where Claude keeps it) or, if that folder is gone, in the project folder; [team] may be null. */
    public LaunchedSession resume(long projectId, String claudeSessionId, Path cwd, Team team) {
        ClaudeCommand.canonicalUuid(claudeSessionId);
        Project project = project(projectId);
        boolean keepsCwd = insideProject(cwd, project.path()) || cwd != null && workDirectories.accepts(project, cwd);
        Path directory = keepsCwd ? cwd : project.path();
        List<Project> members = project.group() ? members(project) : List.of();
        return start(projectId, directory, project.modelSelection(), trustSettings.effective(project), ClaudeCommand.label(LaunchMode.RESUME_PICKER, team), team,
                overlayOf(project, members), (files, exe, selection) -> ClaudeCommand.resumeArguments(exe, files, plugin.directory(), claudeSessionId, selection, team), members);
    }

    static boolean insideProject(Path cwd, Path projectPath) {
        try {
            return cwd != null && Files.isDirectory(cwd) && cwd.toRealPath().startsWith(projectPath.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** A lead session with the team's roles as subagents; the task is its first prompt. */
    public LaunchedSession launchTeam(long projectId, Team team, String task) {
        return start(projectId, ClaudeCommand.label(LaunchMode.NEW, team), team,
                (files, exe, selection) -> ClaudeCommand.teamArguments(exe, files, plugin.directory(), team, task));
    }

    /** A plain session without an agent team; the task is its first prompt. */
    public LaunchedSession launchTask(long projectId, String task) {
        return start(projectId, ClaudeCommand.label(LaunchMode.NEW), null,
                (files, exe, selection) -> ClaudeCommand.taskArguments(exe, files, plugin.directory(), selection, task));
    }

    /** claude attach ID for a background agent; not for a group, whose variadic --add-dir would swallow the subcommand. */
    public LaunchedSession attach(long projectId, String backgroundSessionId) {
        ClaudeCommand.requireSafeBackgroundId(backgroundSessionId);
        Project project = project(projectId);
        if (project.group()) {
            throw new IllegalStateException("Background agents cannot be attached to a project group.");
        }
        return start(projectId, project.path(), project.modelSelection(), trustSettings.effective(project), "attach " + backgroundSessionId, null, SessionOverlay.EMPTY,
                (files, exe, selection) -> ClaudeCommand.attachArguments(exe, files, backgroundSessionId), List.of());
    }

    /** A general session outside any project, started in the assistant directory under {@link #ASSISTANT_PROJECT_ID}. */
    public LaunchedSession launchAssistant() {
        Path directory = dataDir.resolve(ASSISTANT_DIRECTORY);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the assistant folder: " + e.getMessage(), e);
        }
        return start(ASSISTANT_PROJECT_ID, directory, ModelSelection.DEFAULT, TrustLevel.ASK, ClaudeCommand.label(LaunchMode.NEW), null,
                SessionOverlay.EMPTY, (files, exe, selection) -> ClaudeCommand.assistantArguments(exe, files, selection, assistantMcp.toolNames()), List.of());
    }

    private LaunchedSession start(long projectId, String label, Team team, CommandFactory commandFactory) {
        Project project = project(projectId);
        List<Project> members = project.group() ? members(project) : List.of();
        return start(projectId, project.path(), project.modelSelection(), trustSettings.effective(project), label, team, overlayOf(project, members), commandFactory,
                members);
    }

    private LaunchedSession startIn(long projectId, Path directory, String label, Team team, CommandFactory commandFactory) {
        Project project = project(projectId);
        return start(projectId, workDirectories.validate(project, directory), project.modelSelection(), trustSettings.effective(project), label, team,
                overlayOf(project, List.of()), commandFactory, List.of());
    }

    /** A failure to read the overlay means a launch without it, never a failed launch. */
    private SessionOverlay overlayOf(Project project, List<Project> members) {
        try {
            return members.isEmpty() ? overlays.forProject(project.id()) : overlays.forGroup(project.id(), members);
        } catch (RuntimeException e) {
            log.warn("Project overlay of {} skipped: {}", project.id(), e.getClass().getSimpleName());
            return SessionOverlay.EMPTY;
        }
    }

    private LaunchedSession start(long projectId, Path directory, ModelSelection modelSelection, TrustLevel trust, String label, Team team,
            SessionOverlay overlay, CommandFactory commandFactory, List<Project> members) {
        boolean group = !members.isEmpty();
        Path executable = executable();
        String astrolabeSessionId = sessions.register(projectId);
        PtyProcess process = null;
        try {
            List<Path> memberPaths = members.stream().map(Project::path).toList();
            if (group) {
                groupWorkspace.prepare(directory, members);
            }
            SessionFiles files = projectId == ASSISTANT_PROJECT_ID ? hookSettings.writeAssistant(astrolabeSessionId)
                    : group ? writeGroupFiles(astrolabeSessionId, projectMcpServers.approved(memberPaths), codeModeAccess(astrolabeSessionId, projectId, directory, false), overlay, team)
                    : writeProjectFiles(astrolabeSessionId, codeModeAccess(astrolabeSessionId, projectId, directory, true), trust, directory, overlay, team);
            List<String> command = new ArrayList<>(commandFactory.build(files, executable, modelSelection));
            if (!memberPaths.isEmpty()) {
                ClaudeCommand.addDirectories(command, memberPaths);
            }
            String sessionToken = hookSettings.sessionToken(astrolabeSessionId);
            Map<String, String> parent = ClaudeCommand.withSearchPath(System.getenv(), executableLookup.searchPath());
            Map<String, String> environment = group ? ClaudeCommand.groupEnvironment(parent, astrolabeSessionId, sessionToken)
                    : ClaudeCommand.environment(parent, astrolabeSessionId, sessionToken);
            process = PtySpawner.spawn(command, directory, environment);
            watchExit(astrolabeSessionId, process);
            return new LaunchedSession(astrolabeSessionId, projectId, label, process, directory);
        } catch (IOException | RuntimeException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            cleanUp(astrolabeSessionId);
            log.warn("Claude failed to start in {}", directory, e);
            throw new IllegalStateException("Could not start Claude: " + e.getMessage(), e);
        }
    }

    private SessionFiles writeGroupFiles(String astrolabeSessionId, Map<String, JsonNode> memberServers, CodeModeAccess codeMode, SessionOverlay overlay, Team team) {
        var servers = new LinkedHashMap<>(memberServers);
        codeMode.deniedServers().forEach(servers::remove);
        if (overlay.isEmpty() && codeMode == CodeModeAccess.OFF) {
            return hookSettings.write(astrolabeSessionId, servers);
        }
        return hookSettings.write(astrolabeSessionId, servers, codeMode, TrustLevel.ASK, overlay, team);
    }

    private SessionFiles writeProjectFiles(String astrolabeSessionId, CodeModeAccess codeMode, TrustLevel trust, Path directory, SessionOverlay overlay, Team team) {
        if (trust != TrustLevel.ASK) {
            sessionTrust.set(astrolabeSessionId, trust, directory);
        }
        if (!overlay.isEmpty()) {
            return hookSettings.write(astrolabeSessionId, Map.of(), codeMode, trust, overlay, team);
        }
        if (trust != TrustLevel.ASK) {
            return hookSettings.write(astrolabeSessionId, Map.of(), codeMode, trust);
        }
        return codeMode == CodeModeAccess.OFF ? hookSettings.write(astrolabeSessionId) : hookSettings.write(astrolabeSessionId, Map.of(), codeMode);
    }

    /** Not for the Assistant. A group's folder has no .mcp.json to read (its member servers are dropped from the mcp file and denied instead); a failure to read the setting means no Code Mode, never a failed launch. */
    private CodeModeAccess codeModeAccess(String astrolabeSessionId, long projectId, Path directory, boolean readsProjectFile) {
        try {
            var codeMode = codeModes.effective(projectId);
            if (!codeMode.enabled() || codeMode.servers().isEmpty()) {
                return CodeModeAccess.OFF;
            }
            codeModes.rememberDirectory(astrolabeSessionId, directory);
            var inProjectFile = readsProjectFile ? mcpConfigs.read(directory).stream().filter(server -> server.scope() == ExtensionScope.PROJECT)
                    .map(McpServerConfig::name).toList() : List.<String>of();
            return new CodeModeAccess(codeMode.servers().stream().filter(inProjectFile::contains).toList(), codeMode.servers());
        } catch (RuntimeException e) {
            log.warn("Code Mode skipped for project {}: {}", projectId, e.getMessage());
            return CodeModeAccess.OFF;
        }
    }

    private Project project(long projectId) {
        Project project = projects.find(projectId)
                .orElseThrow(() -> new IllegalStateException("The project no longer exists."));
        if (!project.group() && !Files.isDirectory(project.path())) {
            throw new IllegalStateException("The project folder is missing: " + project.path());
        }
        return project;
    }

    private List<Project> members(Project group) {
        List<Project> members = groups.members(group.id());
        if (members.isEmpty()) {
            throw new IllegalStateException("The group has no projects: " + group.name());
        }
        members.stream().filter(member -> !Files.isDirectory(member.path())).findFirst().ifPresent(member -> {
            throw new IllegalStateException("The project folder is missing: " + member.path() + " (" + member.name() + ")");
        });
        return members;
    }

    private Path executable() {
        ClaudeInstallation current = installation.current();
        if (!current.installed() || current.executable() == null) {
            String reason = current.problem() == null ? "Claude Code is not installed." : current.problem();
            throw new IllegalStateException(reason);
        }
        return current.executable();
    }

    private void watchExit(String astrolabeSessionId, PtyProcess process) {
        Thread watcher = new Thread(() -> awaitExit(astrolabeSessionId, process), "claude-exit-" + astrolabeSessionId);
        watcher.setDaemon(true);
        watcher.start();
    }

    private void awaitExit(String astrolabeSessionId, PtyProcess process) {
        try {
            sessions.markEnded(astrolabeSessionId, process.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release(astrolabeSessionId);
        }
    }

    private void cleanUp(String astrolabeSessionId) {
        try {
            sessions.markEnded(astrolabeSessionId, SPAWN_FAILED_EXIT_CODE);
        } finally {
            release(astrolabeSessionId);
        }
    }

    private void release(String astrolabeSessionId) {
        codeModes.forgetDirectory(astrolabeSessionId);
        sessionTrust.forget(astrolabeSessionId);
        deleteSettings(astrolabeSessionId);
    }

    private void deleteSettings(String astrolabeSessionId) {
        try {
            hookSettings.delete(astrolabeSessionId);
        } catch (RuntimeException e) {
            log.warn("Could not delete hook settings of {}: {}", astrolabeSessionId, e.getClass().getName());
        }
    }

    @FunctionalInterface
    private interface CommandFactory {
        List<String> build(SessionFiles files, Path executable, ModelSelection selection);
    }
}
