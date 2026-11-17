package com.github.krpkbhdn.astrolabe.extension.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.ExtensionProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.ProjectOverlays;
import com.github.krpkbhdn.astrolabe.extension.RecordingCli;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServiceTest {

    @TempDir
    Path data;

    private static final Path PROJECT = Path.of("/work/project");
    private final RecordingCli cli = new RecordingCli();
    private final ProjectOverlays overlays = mock(ProjectOverlays.class);
    private final McpService service =
            new McpService(cli, mock(McpConfigReader.class), mock(McpCredentialReader.class), new ExtensionProperties(30, 180, 90, 300, 16384), overlays);

    @Test
    void addPassesTheJsonAsOneArgumentInTheProjectDirectory() {
        var json = "{\"type\":\"http\",\"url\":\"https://x\"}";

        service.add("my-server", json, ExtensionScope.LOCAL, PROJECT);

        assertThat(cli.lastCall()).containsExactly("mcp", "add-json", "--scope", "local", "my-server", json);
        assertThat(cli.directories).containsExactly(PROJECT);
    }

    @Test
    void addRejectsNamesThatCouldBeTakenForFlagsAndNonObjectJson() {
        assertThatThrownBy(() -> service.add("--evil", "{}", ExtensionScope.USER, null)).isInstanceOf(ExtensionException.class);
        assertThatThrownBy(() -> service.add("ok", "[1]", ExtensionScope.USER, null)).isInstanceOf(ExtensionException.class);
        assertThatThrownBy(() -> service.add("ok", "not json", ExtensionScope.USER, null)).isInstanceOf(ExtensionException.class);
        assertThat(cli.calls).isEmpty();
    }

    @Test
    void failedAddRedactsSingleEnvAndHeaderValuesEchoedAlone() {
        var json = "{\"command\":\"x\",\"env\":{\"TOKEN\":\"SECRET1\"},\"headers\":{\"Authorization\":\"Bearer SECRET2\"}}";
        cli.answering(1, "", "bad env TOKEN=SECRET1 header Bearer SECRET2");

        assertThatThrownBy(() -> service.add("s", json, ExtensionScope.USER, null))
                .isInstanceOfSatisfying(ExtensionException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("SECRET1", "SECRET2");
                    assertThat(e.output()).doesNotContain("SECRET1", "SECRET2");
                });
    }

    @Test
    void failedAddNeverEchoesTheConfigJson() {
        var json = "{\"command\":\"x\",\"env\":{\"TOKEN\":\"SECRET\"}}";
        cli.answering(1, "", "Invalid config " + json);

        assertThatThrownBy(() -> service.add("s", json, ExtensionScope.USER, null))
                .isInstanceOfSatisfying(ExtensionException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("SECRET").contains("<config>");
                    assertThat(e.output()).doesNotContain("SECRET");
                });
    }

    @Test
    void failedAddRedactsAnEchoedUrlAndCommandLine() {
        var json = "{\"type\":\"http\",\"url\":\"https://h/mcp?key=SECRET3\",\"command\":\"npx\",\"args\":[\"srv\",\"--token\",\"SECRET4\"]}";
        cli.answering(1, "", "bad url https://h/mcp?key=SECRET3 and npx srv --token SECRET4");

        assertThatThrownBy(() -> service.add("s", json, ExtensionScope.USER, null))
                .isInstanceOfSatisfying(ExtensionException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("SECRET3", "SECRET4");
                    assertThat(e.output()).doesNotContain("SECRET3", "SECRET4");
                });
    }

    @Test
    void addRejectsListSeparatorsInNamesButExistingOnesCanStillBeRemoved() {
        assertThatThrownBy(() -> service.add("a: b", "{}", ExtensionScope.USER, null)).isInstanceOf(ExtensionException.class);
        assertThatThrownBy(() -> service.add("a - b", "{}", ExtensionScope.USER, null)).isInstanceOf(ExtensionException.class);
        assertThat(cli.calls).isEmpty();

        service.remove("a: b", ExtensionScope.USER, null);
        service.logout("a - b", PROJECT);

        assertThat(cli.calls).hasSize(2);
    }

    @Test
    void loginUsesTheProjectDirectoryAndRejectsFlagLikeNames() {
        service.login("claude.ai Canva", PROJECT, url -> {});
        service.logout("plugin:context7:context7", PROJECT);

        assertThat(cli.calls).containsExactly(List.of("mcp", "login", "claude.ai Canva"), List.of("mcp", "logout", "plugin:context7:context7"));
        assertThat(cli.directories).containsExactly(PROJECT, PROJECT);
        assertThatThrownBy(() -> service.login("--no-browser", PROJECT, url -> {})).isInstanceOf(ExtensionException.class);
    }

    @Test
    void loginRunsInATerminalReportsTheSignInPageOnceAndExplainsFailures() {
        var urls = new java.util.ArrayList<String>();
        cli.answering(1, "Couldn't complete authentication: boom\n[mcp-sdk] noise\nIf the browser didn't open, visit:\n  https://x.test/auth?s=1\nWaiting for authorization", "");

        assertThatThrownBy(() -> service.login("s", PROJECT, urls::add))
                .isInstanceOf(ExtensionException.class)
                .hasMessage("claude mcp login failed: Couldn't complete authentication: boom");
        assertThat(cli.terminalCalls).containsExactly(List.of("mcp", "login", "s"));
        assertThat(urls).containsExactly("https://x.test/auth?s=1");
    }

    @Test
    void removeUsesTheScope() {
        service.remove("s", ExtensionScope.USER, null);

        assertThat(cli.lastCall()).containsExactly("mcp", "remove", "--scope", "user", "s");
    }

    @Test
    void healthParsesTheListOutput() {
        cli.answering("Checking MCP server health…\n\na: http://x - ✔ Connected\n");

        assertThat(service.health(PROJECT)).extracting(McpHealth::status).containsExactly(McpStatus.CONNECTED);
        assertThat(cli.directories).containsExactly(PROJECT);
    }

    @Test
    void cliFailureKeepsTheMessage() {
        cli.answering(1, "", "No MCP server found with name: zz");

        assertThatThrownBy(() -> service.remove("zz", ExtensionScope.USER, null))
                .hasMessageContaining("No MCP server found with name: zz");
    }

    @Test
    void projectScopeOfARegisteredProjectIsWrittenToTheOverlayWithoutCallingClaude() throws Exception {
        var overlay = new ProjectOverlay(data.resolve("overlay"));
        when(overlays.of(PROJECT)).thenReturn(java.util.Optional.of(overlay));

        service.add("db", "{\"command\":\"x\"}", ExtensionScope.PROJECT, PROJECT);
        service.add("web", "{\"type\":\"http\",\"url\":\"https://x\"}", ExtensionScope.APP, PROJECT);

        assertThat(cli.calls).isEmpty();
        assertThat(Files.readString(overlay.mcpFile())).contains("\"db\"", "\"web\"", "mcpServers");
        assertThatThrownBy(() -> service.add("db", "{}", ExtensionScope.PROJECT, PROJECT)).isInstanceOf(ExtensionException.class).hasMessageContaining("already exists");

        service.remove("db", ExtensionScope.APP, PROJECT);

        assertThat(Files.readString(overlay.mcpFile())).doesNotContain("\"db\"").contains("\"web\"");
        assertThat(cli.calls).isEmpty();
    }

    @Test
    void aNameOfAnInProjectOrUserServerIsRefusedForTheOverlay() {
        var overlay = new ProjectOverlay(data.resolve("overlay"));
        when(overlays.of(PROJECT)).thenReturn(java.util.Optional.of(overlay));
        var reader = mock(McpConfigReader.class);
        when(reader.read(PROJECT)).thenReturn(java.util.List.of(
                new McpServerConfig("inproject", ExtensionScope.PROJECT, "stdio", "x", McpApproval.NOT_REQUIRED),
                new McpServerConfig("mine", ExtensionScope.USER, "stdio", "x", McpApproval.NOT_REQUIRED)));
        var clashing = new McpService(cli, reader, mock(McpCredentialReader.class), new ExtensionProperties(30, 180, 90, 300, 16384), overlays);

        assertThatThrownBy(() -> clashing.add("inproject", "{\"command\":\"x\"}", ExtensionScope.APP, PROJECT)).isInstanceOf(ExtensionException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> clashing.add("mine", "{\"command\":\"x\"}", ExtensionScope.APP, PROJECT)).isInstanceOf(ExtensionException.class).hasMessageContaining("already exists");
        assertThat(overlay.mcpFile()).doesNotExist();
    }

    @Test
    void removingAnInFolderServerOfARegisteredProjectStillUsesTheCliAndAppScopeNeedsAProject() {
        when(overlays.of(PROJECT)).thenReturn(java.util.Optional.of(new ProjectOverlay(data.resolve("overlay"))));

        service.remove("old", ExtensionScope.PROJECT, PROJECT);

        assertThat(cli.lastCall()).containsExactly("mcp", "remove", "--scope", "project", "old");
        assertThatThrownBy(() -> service.add("x", "{}", ExtensionScope.APP, Path.of("/unregistered"))).isInstanceOf(ExtensionException.class);
    }

    @Test
    void anOverlayFileThatIsNotAnObjectIsRefusedAndKept() throws Exception {
        var overlay = new ProjectOverlay(data.resolve("overlay"));
        overlay.write(overlay.mcpFile(), "[1]");
        when(overlays.of(PROJECT)).thenReturn(java.util.Optional.of(overlay));

        assertThatThrownBy(() -> service.add("db", "{}", ExtensionScope.APP, PROJECT)).isInstanceOf(ExtensionException.class);
        assertThat(Files.readString(overlay.mcpFile())).isEqualTo("[1]");
    }
}
