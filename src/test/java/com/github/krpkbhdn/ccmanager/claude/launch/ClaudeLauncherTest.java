package com.github.krpkbhdn.ccmanager.claude.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.toolchain.ExecutableLookup;
import com.github.krpkbhdn.ccmanager.claude.hook.HookSettingsWriter;
import com.github.krpkbhdn.ccmanager.claude.hook.SessionFiles;
import com.github.krpkbhdn.ccmanager.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeInstallation;
import com.github.krpkbhdn.ccmanager.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.ccmanager.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.ccmanager.claude.proposal.SessionTrust;
import com.github.krpkbhdn.ccmanager.claude.session.SessionRegistry;
import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.extension.mcp.ProjectMcpServers;
import com.github.krpkbhdn.ccmanager.git.GitWorktreeService;
import com.github.krpkbhdn.ccmanager.git.Worktree;
import com.github.krpkbhdn.ccmanager.project.GroupWorkspace;
import com.github.krpkbhdn.ccmanager.project.ModelSelection;
import com.github.krpkbhdn.ccmanager.project.ProjectGroupService;
import java.util.List;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.claude.hook.CodeModeAccess;
import java.util.Map;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import com.github.krpkbhdn.ccmanager.project.TrustSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeLauncherTest {

    private final ProjectService projects = mock(ProjectService.class);
    private final ClaudeInstallationService installation = mock(ClaudeInstallationService.class);
    private final HookSettingsWriter hooks = mock(HookSettingsWriter.class);
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final ProjectGroupService groups = mock(ProjectGroupService.class);
    private final AssistantMcpServer assistantEndpoint = mock(AssistantMcpServer.class);
    private final ProjectCodeModeService codeModes = mock(ProjectCodeModeService.class);
    private final McpConfigReader mcpConfigs = mock(McpConfigReader.class);
    private final GitWorktreeService worktrees = mock(GitWorktreeService.class);
    private final SessionTrust sessionTrust = mock(SessionTrust.class);
    private final TrustSettingsService trustSettings = mock(TrustSettingsService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final Path dataDir = Path.of("build", "launcher-test-data").toAbsolutePath();
    private final ClaudeLauncher launcher = new ClaudeLauncher(projects, installation, hooks, mock(ClaudePluginWriter.class), sessions, new AppProperties(dataDir),
            groups, mock(GroupWorkspace.class), mock(ProjectMcpServers.class), assistantEndpoint, new WorkDirectories(worktrees),
            codeModes, mcpConfigs, mock(ExecutableLookup.class), sessionTrust, trustSettings);

    {
        org.mockito.Mockito.doReturn(TrustLevel.ASK).when(trustSettings).defaultLevel();
    }

    @TempDir
    Path directory;

    private Worktree worktreeAt(Path path) {
        return new Worktree(path, "task", "abc", false, false, false, false, false, false);
    }

    private void claudeInstalledAndSpawnFails() throws Exception {
        Path notExecutable = Files.createFile(directory.resolve("claude-fake"));
        when(installation.current()).thenReturn(new ClaudeInstallation(true, notExecutable, "1", null, null));
        when(sessions.register(1)).thenReturn("ccm-1");
        when(hooks.write("ccm-1")).thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));
    }

    @Test
    void launchInRefusesAForeignDirectory() throws Exception {
        var foreign = Files.createDirectories(directory.resolveSibling(directory.getFileName() + "-foreign"));
        when(worktrees.list(directory)).thenReturn(List.of(worktreeAt(directory)));

        assertThatThrownBy(() -> launcher.launchIn(1, foreign, LaunchMode.NEW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> launcher.launchTaskIn(1, foreign, null, "t")).isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void launchInAcceptsALinkedWorktreeOfTheProject() throws Exception {
        var worktree = Files.createDirectories(directory.resolveSibling(directory.getFileName() + "-wt"));
        when(worktrees.list(directory)).thenReturn(List.of(worktreeAt(directory), worktreeAt(worktree)));
        claudeInstalledAndSpawnFails();

        assertThatThrownBy(() -> launcher.launchTaskIn(1, worktree, null, "t")).hasMessageStartingWith("Could not start Claude");
        verify(sessions).register(1);
    }

    @Test
    void launchInAcceptsTheProjectFolderWithoutAskingGit() throws Exception {
        claudeInstalledAndSpawnFails();

        assertThatThrownBy(() -> launcher.launchIn(1, directory, LaunchMode.NEW, null)).hasMessageStartingWith("Could not start Claude");
        verify(sessions).register(1);
        verify(worktrees, never()).list(directory);
    }

    @Test
    void aFailedSpawnForgetsTheRememberedCodeModeDirectory() throws Exception {
        claudeInstalledAndSpawnFails();

        assertThatThrownBy(() -> launcher.launchIn(1, directory, LaunchMode.NEW, null)).hasMessageStartingWith("Could not start Claude");
        verify(codeModes).forgetDirectory("ccm-1");
    }

    @Test
    void launchInIsRefusedForAGroup() {
        when(projects.find(2)).thenReturn(Optional.of(group()));

        assertThatThrownBy(() -> launcher.launchIn(2, directory, LaunchMode.NEW, null)).isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void resumeKeepsAWorktreeCwdAndFallsBackForAForeignOne() throws Exception {
        var worktree = Files.createDirectories(directory.resolveSibling(directory.getFileName() + "-wt"));
        var foreign = Files.createDirectories(directory.resolveSibling(directory.getFileName() + "-foreign"));
        when(worktrees.list(directory)).thenReturn(List.of(worktreeAt(directory), worktreeAt(worktree)));

        assertThat(new WorkDirectories(worktrees).accepts(projects.find(1).orElseThrow(), worktree)).isTrue();
        assertThat(new WorkDirectories(worktrees).accepts(projects.find(1).orElseThrow(), foreign)).isFalse();
    }

    @BeforeEach
    void projectExists() {
        when(projects.find(anyLong())).thenReturn(Optional.of(new Project(1, "p", directory, Instant.now())));
    }

    @Test
    void resumeRejectsInvalidSessionIdBeforeStartingAnything() {
        assertThatThrownBy(() -> launcher.resume(1, "not-a-uuid; rm -rf", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void attachRejectsUnsafeIdBeforeRegisteringASession() {
        assertThatThrownBy(() -> launcher.attach(1, "--dangerous")).isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void resumeDirectoryMustBeInsideTheProject() throws Exception {
        var inside = Files.createDirectories(directory.resolve("sub"));
        var outside = Files.createDirectories(directory.resolveSibling(directory.getFileName() + "-elsewhere"));

        assertThat(ClaudeLauncher.insideProject(inside, directory)).isTrue();
        assertThat(ClaudeLauncher.insideProject(outside, directory)).isFalse();
        assertThat(ClaudeLauncher.insideProject(directory.resolve("missing"), directory)).isFalse();
        assertThat(ClaudeLauncher.insideProject(null, directory)).isFalse();
    }

    @Test
    void resumeWithMissingClaudeGivesReadableMessageAndRegistersNothing() {
        when(installation.current()).thenReturn(new ClaudeInstallation(false, null, null, null, "Claude is missing"));

        assertThatThrownBy(() -> launcher.resume(1, "123e4567-e89b-12d3-a456-426614174000", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Claude is missing");
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void missingClaudeGivesReadableMessageAndRegistersNothing() {
        when(installation.current()).thenReturn(new ClaudeInstallation(false, null, null, null, "Claude is missing"));

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW))
                .isInstanceOf(IllegalStateException.class).hasMessage("Claude is missing");
        verify(sessions, never()).register(anyLong());
    }

    private Project group() {
        return new Project(2, "g", directory, Instant.now(), ModelSelection.DEFAULT, true, null);
    }

    @Test
    void groupWithoutMembersIsRefused() {
        when(projects.find(2)).thenReturn(Optional.of(group()));
        when(groups.members(2)).thenReturn(List.of());
        when(installation.current()).thenReturn(new ClaudeInstallation(true, directory, "1", null, null));

        assertThatThrownBy(() -> launcher.launch(2, LaunchMode.NEW)).hasMessageContaining("no projects");
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void groupWithMissingMemberFolderNamesIt() {
        var gone = new Project(3, "gone", directory.resolve("nope"), Instant.now());
        when(projects.find(2)).thenReturn(Optional.of(group()));
        when(groups.members(2)).thenReturn(List.of(gone));

        assertThatThrownBy(() -> launcher.launch(2, LaunchMode.NEW)).hasMessageContaining("folder is missing").hasMessageContaining("gone");
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void attachIsRefusedForAGroup() {
        when(projects.find(2)).thenReturn(Optional.of(group()));

        assertThatThrownBy(() -> launcher.attach(2, "ab12")).hasMessageContaining("group");
        verify(sessions, never()).register(anyLong());
    }

    @Test
    void unknownProjectIsReported() {
        when(projects.find(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW)).hasMessageContaining("project");
    }

    @Test
    void spawnFailureEndsSessionAndDeletesSettings() throws Exception {
        Path notExecutable = Files.createFile(directory.resolve("claude-fake"));
        when(installation.current()).thenReturn(new ClaudeInstallation(true, notExecutable, "1", null, null));
        when(sessions.register(1)).thenReturn("ccm-1");
        when(hooks.write("ccm-1")).thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Could not start Claude");

        verify(sessions).markEnded("ccm-1", -1);
        verify(hooks).delete("ccm-1");
    }

    @Test
    void assistantRegistersWithoutProjectAndCreatesItsDirectory() throws Exception {
        Path notExecutable = Files.createFile(directory.resolve("claude-fake"));
        when(installation.current()).thenReturn(new ClaudeInstallation(true, notExecutable, "1", null, null));
        when(sessions.register(ClaudeLauncher.ASSISTANT_PROJECT_ID)).thenReturn("ccm-0");
        when(assistantEndpoint.toolNames()).thenReturn(List.of("list_projects"));
        when(hooks.write("ccm-0")).thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(launcher::launchAssistant).hasMessageStartingWith("Could not start Claude");

        verify(sessions).register(0L);
        verify(projects, never()).find(anyLong());
        assertThat(dataDir.resolve("assistant")).isDirectory();
    }

    private CodeModeAccess launchWithCodeMode(ProjectCodeMode mode, List<McpServerConfig> servers) throws Exception {
        claudeInstalledAndSpawnFails();
        var files = new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json"));
        when(codeModes.get(1)).thenReturn(mode);
        when(mcpConfigs.read(directory)).thenReturn(servers);
        when(hooks.write(org.mockito.ArgumentMatchers.eq("ccm-1"), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any())).thenReturn(files);
        assertThatThrownBy(() -> launcher.launchTask(1, "t")).isInstanceOf(IllegalStateException.class);
        var captor = org.mockito.ArgumentCaptor.forClass(CodeModeAccess.class);
        verify(hooks).write(org.mockito.ArgumentMatchers.eq("ccm-1"), org.mockito.ArgumentMatchers.anyMap(), captor.capture());
        return captor.getValue();
    }

    private static McpServerConfig definition(String name, ExtensionScope scope) {
        return new McpServerConfig(name, scope, "http", "http://x", McpApproval.NOT_REQUIRED);
    }

    @Test
    void codeModeHidesProjectFileServersAndDeniesTheRest() throws Exception {
        var access = launchWithCodeMode(new ProjectCodeMode(1, true, List.of("jira", "files"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK),
                List.of(definition("jira", ExtensionScope.PROJECT), definition("files", ExtensionScope.USER)));

        assertThat(access.hiddenProjectServers()).containsExactly("jira");
        assertThat(access.deniedServers()).containsExactly("jira", "files");
    }

    @Test
    void codeModeOffOrWithoutServersWritesTheUnchangedFiles() throws Exception {
        claudeInstalledAndSpawnFails();
        when(codeModes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of("jira"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));

        assertThatThrownBy(() -> launcher.launchTask(1, "t")).isInstanceOf(IllegalStateException.class);
        verify(hooks).write("ccm-1");
        verify(hooks, never()).write(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assistantNeverRegistersATrustLevel() throws Exception {
        Path notExecutable = Files.createFile(directory.resolve("claude-fake"));
        when(installation.current()).thenReturn(new ClaudeInstallation(true, notExecutable, "1", null, null));
        when(sessions.register(ClaudeLauncher.ASSISTANT_PROJECT_ID)).thenReturn("ccm-0");
        when(assistantEndpoint.toolNames()).thenReturn(List.of("list_projects"));
        when(hooks.writeAssistant("ccm-0")).thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(launcher::launchAssistant).isInstanceOf(IllegalStateException.class);

        verify(sessionTrust, never()).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void groupNeverRegistersATrustLevelEvenWhenItsProjectsAreTrusted() throws Exception {
        Path notExecutable = Files.createFile(directory.resolve("claude-fake"));
        when(installation.current()).thenReturn(new ClaudeInstallation(true, notExecutable, "1", null, null));
        var member = new Project(3, "m", directory, Instant.now(), ModelSelection.DEFAULT, false, null, TrustLevel.AUTONOMOUS);
        when(projects.find(2)).thenReturn(Optional.of(new Project(2, "g", directory, Instant.now(), ModelSelection.DEFAULT, true, null, TrustLevel.AUTONOMOUS)));
        when(groups.members(2)).thenReturn(List.of(member));
        when(sessions.register(2)).thenReturn("ccm-2");

        assertThatThrownBy(() -> launcher.launch(2, LaunchMode.NEW)).isInstanceOf(IllegalStateException.class);

        verify(sessionTrust, never()).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aTrustedProjectRegistersItsLevelAndFolderAndWritesTrustedSettings() throws Exception {
        claudeInstalledAndSpawnFails();
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", directory, Instant.now(), ModelSelection.DEFAULT, false, null, TrustLevel.TRUSTED)));
        when(codeModes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of(), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(hooks.write("ccm-1", Map.of(), CodeModeAccess.OFF, TrustLevel.TRUSTED))
                .thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW)).isInstanceOf(IllegalStateException.class);

        verify(sessionTrust).set("ccm-1", TrustLevel.TRUSTED, directory);
        verify(sessionTrust).forget("ccm-1");
    }

    @Test
    void aProjectWithoutOverrideStartsWithTheAppDefaultLevel() throws Exception {
        claudeInstalledAndSpawnFails();
        org.mockito.Mockito.doReturn(TrustLevel.AUTONOMOUS).when(trustSettings).defaultLevel();
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", directory, Instant.now())));
        when(codeModes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of(), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(hooks.write("ccm-1", Map.of(), CodeModeAccess.OFF, TrustLevel.AUTONOMOUS))
                .thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW)).isInstanceOf(IllegalStateException.class);

        verify(sessionTrust).set("ccm-1", TrustLevel.AUTONOMOUS, directory);
    }

    @Test
    void anOverrideBeatsTheAppDefault() throws Exception {
        claudeInstalledAndSpawnFails();
        org.mockito.Mockito.doReturn(TrustLevel.AUTONOMOUS).when(trustSettings).defaultLevel();
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", directory, Instant.now(), ModelSelection.DEFAULT, false, null, TrustLevel.ASK)));
        when(codeModes.get(1)).thenReturn(new ProjectCodeMode(1, false, List.of(), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(hooks.write("ccm-1")).thenReturn(new SessionFiles(directory.resolve("s.json"), directory.resolve("m.json")));

        assertThatThrownBy(() -> launcher.launch(1, LaunchMode.NEW)).isInstanceOf(IllegalStateException.class);

        verify(sessionTrust, never()).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
