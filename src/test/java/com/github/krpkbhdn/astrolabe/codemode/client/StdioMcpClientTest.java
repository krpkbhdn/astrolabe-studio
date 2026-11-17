package com.github.krpkbhdn.astrolabe.codemode.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StdioMcpClientTest {

    private McpClient client;

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
        }
    }

    private McpClient start(Duration timeout) {
        return start(timeout, null);
    }

    private McpClient start(Duration timeout, Path workingDirectory) {
        return start(timeout, workingDirectory, Map.of());
    }

    private McpClient start(Duration timeout, Path workingDirectory, Map<String, String> env) {
        var java = ProcessHandle.current().info().command().orElseThrow();
        var definition = new McpServerDefinition("fake", ExtensionScope.USER, McpServerDefinition.Transport.STDIO, java,
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpStdioServer.class.getName()), env, "", Map.of(), workingDirectory);
        var properties = TestProperties.of(timeout, 1, 1, 1, Duration.ofMinutes(1), "image", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var host = new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, Path.of("."));
        client = new McpClients(properties, host).create(definition);
        client.initialize();
        return client;
    }

    @Test
    void listsToolsAcrossPagesWithAnnotationDefaults() {
        var tools = start(Duration.ofSeconds(20)).listTools();

        assertThat(tools).extracting("name", "readOnlyHint", "destructiveHint").startsWith(
                org.assertj.core.groups.Tuple.tuple("plain", false, true), org.assertj.core.groups.Tuple.tuple("read", true, false));
        assertThat(tools.get(0).outputSchema()).isEmpty();
        assertThat(tools.get(1).outputSchema()).isPresent();
        assertThat(tools.get(1).server()).isEqualTo("fake");
    }

    @Test
    void annotationHintsDecideWhetherAToolRunsUnasked() {
        var byName = start(Duration.ofSeconds(20)).listTools().stream().collect(java.util.stream.Collectors.toMap(tool -> tool.name(), tool -> tool));

        assertThat(byName.get("plain").isSafeToRunUnasked()).isFalse();
        assertThat(byName.get("plain").destructiveHint()).isTrue();
        assertThat(byName.get("read").isSafeToRunUnasked()).isTrue();
        assertThat(byName.get("only_read_only").isSafeToRunUnasked()).isTrue();
        assertThat(byName.get("contradictory").isSafeToRunUnasked()).isFalse();
        assertThat(byName.get("only_destructive_false").isSafeToRunUnasked()).isFalse();
    }

    @Test
    void callsAToolWithArguments() throws Exception {
        var result = start(Duration.ofSeconds(20)).callTool("echo", new JsonMapper().readTree("{\"a\":1}"));

        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void serverStartsInTheWorkingDirectory(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var result = start(Duration.ofSeconds(20), directory).callTool("cwd", null);

        assertThat(Path.of(result.path("content").get(0).path("text").asString()).toRealPath()).isEqualTo(directory.toRealPath());
    }

    @Test
    void oversizedLineFailsTheCallAndEndsTheConnection() {
        var started = start(Duration.ofSeconds(60));

        assertThatThrownBy(() -> started.callTool("huge", null)).isInstanceOf(McpClientException.class).hasMessage(JsonRpcMcpClient.RESPONSE_TOO_LARGE);
        assertThatThrownBy(() -> started.callTool("echo", null)).isInstanceOf(McpClientException.class);
    }

    @Test
    void startFailureNamesNeitherTheCommandNorItsArguments() {
        var definition = new McpServerDefinition("fake", ExtensionScope.USER, McpServerDefinition.Transport.STDIO, "no-such-binary-xyz",
                List.of("--token=hunter2"), Map.of(), "", Map.of(), null);
        var properties = TestProperties.of(Duration.ofSeconds(5), 1, 1, 1, Duration.ofMinutes(1), "image", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        client = new McpClients(properties, new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, Path.of("."))).create(definition);

        assertThatThrownBy(client::initialize).isInstanceOf(McpClientException.class).hasMessageNotContaining("hunter2").hasMessageNotContaining("no-such-binary-xyz");
    }

    @Test
    void errorResponseBecomesAnException() {
        var started = start(Duration.ofSeconds(20));

        assertThatThrownBy(() -> started.callTool("fail", null)).isInstanceOf(McpClientException.class).hasMessageContaining("boom");
    }

    @Test
    void unansweredRequestTimesOut() {
        var started = start(Duration.ofSeconds(3));

        assertThatThrownBy(() -> started.callTool("never", null)).isInstanceOf(McpClientException.class).hasMessageContaining("in time");
    }

    @Test
    void errorResponseIsAServerErrorWhileTransportFailuresAreNot() {
        var started = start(Duration.ofSeconds(3));

        assertThatThrownBy(() -> started.callTool("fail", null)).isInstanceOf(McpServerErrorException.class);
        assertThatThrownBy(() -> started.callTool("never", null)).isNotInstanceOf(McpServerErrorException.class);
    }

    @Test
    void serverRequestDuringTheHandshakeIsAnsweredWithoutStalling() {
        var started = start(Duration.ofSeconds(20), null, Map.of("FAKE_REQUEST_DURING_INIT", "1"));

        assertThat(started.listTools()).isNotEmpty();
    }

    @Test
    void closeReturnsPromptlyWhileACallIsPending(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var received = temp.resolve("received");
        var started = start(Duration.ofSeconds(60), null, Map.of("FAKE_NEVER_FLAG", received.toString()));
        var call = java.util.concurrent.CompletableFuture.runAsync(() -> started.callTool("never", null));
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!java.nio.file.Files.exists(received) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(received).exists();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), started::close);
        assertThatThrownBy(() -> call.get(10, java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(McpClientException.class);
    }

    @Test
    void aServerThatStopsReadingFailsTheCallInsteadOfBlockingTheWriter() {
        var started = start(Duration.ofSeconds(3));
        started.callTool("stopreading", null);
        var flood = JsonMapper.builder().build().createObjectNode().put("blob", "x".repeat(4 * 1024 * 1024));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThatThrownBy(() -> started.callTool("echo", flood)).isInstanceOf(McpClientException.class).hasMessageContaining("not accepting input in time"));
        assertThatThrownBy(() -> started.callTool("echo", null)).isInstanceOf(McpClientException.class);
    }

    @Test
    void closeBeforeStartPreventsAnyLaterProcess() {
        var definition = new McpServerDefinition("fake", ExtensionScope.USER, McpServerDefinition.Transport.STDIO, ProcessHandle.current().info().command().orElseThrow(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpStdioServer.class.getName()), Map.of(), "", Map.of(), null);
        var properties = TestProperties.of(Duration.ofSeconds(5), 1, 1, 1, Duration.ofMinutes(1), "image", "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        client = new McpClients(properties, new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, Path.of("."))).create(definition);
        var before = ProcessHandle.current().children().count();

        client.close();

        assertThatThrownBy(client::initialize).isInstanceOf(McpClientException.class).hasMessageContaining("closed");
        assertThat(ProcessHandle.current().children().count()).isLessThanOrEqualTo(before);
    }

    @Test
    void usingTheClientAfterCloseFailsWithAFixedMessage() {
        var started = start(Duration.ofSeconds(20));
        started.close();

        assertThatThrownBy(() -> started.callTool("echo", null)).isInstanceOf(McpClientException.class).hasMessageNotContaining("null");
    }

    @Test
    void serverInitiatedElicitationIsDeclined() {
        var result = start(Duration.ofSeconds(20)).callTool("elicit", null);

        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("declined -32601");
    }
}
