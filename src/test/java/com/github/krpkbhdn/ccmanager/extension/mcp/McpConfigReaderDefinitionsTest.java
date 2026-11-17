package com.github.krpkbhdn.ccmanager.extension.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.git.GitRunner;
import com.github.krpkbhdn.ccmanager.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigReaderDefinitionsTest {

    @TempDir
    Path home;

    @TempDir
    Path project;

    @Test
    void keepsEnvAndHeadersAndExpandsVariables() throws IOException {
        Files.writeString(home.resolve(".claude.json"), """
                {"mcpServers":{"remote":{"type":"http","url":"https://h/${SEG:-mcp}","headers":{"Authorization":"Bearer ${TOKEN}"}}}}
                """);
        Files.writeString(project.resolve(".mcp.json"), """
                {"mcpServers":{"local":{"command":"${BIN:-node}","args":["a","${UNSET_ONE}","${HOME_DIR}/x"],"env":{"KEY":"${TOKEN}"}}}}
                """);
        var host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, home).withEnv("TOKEN", "s3cret").withEnv("HOME_DIR", "/home/u");

        var definitions = new McpConfigReader(host, new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class)).definitions(project);

        assertThat(definitions).hasSize(2);
        var remote = definitions.get(0);
        assertThat(remote.transport()).isEqualTo(McpServerDefinition.Transport.HTTP);
        assertThat(remote.scope()).isEqualTo(ExtensionScope.USER);
        assertThat(remote.url()).isEqualTo("https://h/mcp");
        assertThat(remote.headers()).containsEntry("Authorization", "Bearer s3cret");
        var local = definitions.get(1);
        assertThat(local.transport()).isEqualTo(McpServerDefinition.Transport.STDIO);
        assertThat(local.command()).isEqualTo("node");
        assertThat(local.args()).containsExactly("a", "${UNSET_ONE}", "/home/u/x");
        assertThat(local.env()).containsEntry("KEY", "s3cret");
    }

    @Test
    void everyScopeStartsInTheGivenDirectoryAndNoneWithoutOne() throws IOException {
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\":{\"u\":{\"command\":\"x\"}}}");
        Files.writeString(project.resolve(".mcp.json"), "{\"mcpServers\":{\"p\":{\"command\":\"y\"}}}");
        var reader = new McpConfigReader(new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, home), new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class));

        assertThat(reader.definitions(project)).extracting(McpServerDefinition::workingDirectory).containsExactly(project, project);
        assertThat(reader.definitions(null)).extracting(McpServerDefinition::workingDirectory).containsOnlyNulls();
    }

    @Test
    void toStringNeverShowsEnvOrHeaderValues() throws IOException {
        Files.writeString(project.resolve(".mcp.json"), """
                {"mcpServers":{"a":{"command":"x","args":["--token=argsecret"],"env":{"KEY":"envsecret"}},
                               "b":{"url":"https://h/p?k=urlsecret","headers":{"Authorization":"headersecret"}}}}
                """);
        var host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, home);

        var text = new McpConfigReader(host, new GitRunner(), org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.ProjectOverlays.class)).definitions(project).toString();

        assertThat(text).contains("KEY", "Authorization").doesNotContain("envsecret", "headersecret", "argsecret", "urlsecret");
    }
}
