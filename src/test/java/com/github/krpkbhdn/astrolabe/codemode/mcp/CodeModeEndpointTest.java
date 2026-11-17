package com.github.krpkbhdn.astrolabe.codemode.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.mcp.McpEndpoint;
import com.github.krpkbhdn.astrolabe.claude.mcp.McpTool;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeService;
import com.github.krpkbhdn.astrolabe.codemode.RunCancellation;
import com.github.krpkbhdn.astrolabe.codemode.ScriptResult;
import com.github.krpkbhdn.astrolabe.codemode.ServerConnections;
import com.github.krpkbhdn.astrolabe.codemode.ServerStatus;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CodeModeEndpointTest {

    private static final SessionContext SESSION = new SessionContext(7, "s1");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CodeModeService service = mock(CodeModeService.class);
    private final ServerConnections connections = mock(ServerConnections.class);
    private CodeModeEndpoint endpoint;

    @BeforeEach
    void setUp() {
        var sessions = mock(SessionRegistry.class);
        var snapshot = mock(SessionSnapshot.class);
        when(snapshot.projectId()).thenReturn(7L);
        when(sessions.find("s1")).thenReturn(Optional.of(snapshot));
        endpoint = new CodeModeEndpoint(List.of(new ListServersTool(service), new SearchToolsTool(service), new ExecuteTool(service)), sessions, connections, service, mapper);
    }

    private JsonNode send(String sessionId, String json) {
        return mapper.readTree(endpoint.handle(sessionId, json.getBytes(StandardCharsets.UTF_8)).orElseThrow());
    }

    private JsonNode callTool(String name, String arguments) {
        return send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}").path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    private void cancel(String sessionId, String requestIdJson) {
        var body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + requestIdJson + ",\"reason\":\"x\"}}";
        assertThat(endpoint.handle(sessionId, body.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    /** @return the cancellation the execute request carried, cancelled or not by the notification sent during it */
    private RunCancellation executeCancelledBy(String sessionId, String requestIdJson, String cancelledIdJson) {
        var carried = new AtomicReference<RunCancellation>();
        when(service.execute(any(), any(), any())).thenAnswer(call -> {
            carried.set(call.getArgument(2));
            cancel(sessionId, cancelledIdJson);
            return ScriptResult.failed("The script was cancelled");
        });
        send("s1", "{\"jsonrpc\":\"2.0\",\"id\":" + requestIdJson + ",\"method\":\"tools/call\",\"params\":{\"name\":\"execute\",\"arguments\":{\"code\":\"1\"}}}");
        return carried.get();
    }

    @Test
    void cancellingTheInFlightExecuteCancelsThatRequestForNumberAndStringIds() {
        assertThat(executeCancelledBy("s1", "5", "5").isCancelled()).isTrue();
        assertThat(executeCancelledBy("s1", "\"abc\"", "\"abc\"").isCancelled()).isTrue();
    }

    @Test
    void cancelOfAnUnknownIdOfTheOtherTypeOrOfAnotherSessionStopsNothing() {
        assertThat(executeCancelledBy("s1", "5", "6").isCancelled()).isFalse();
        assertThat(executeCancelledBy("s1", "5", "\"5\"").isCancelled()).isFalse();
        assertThat(executeCancelledBy("s2", "5", "5").isCancelled()).isFalse();
        cancel("s1", "5");
    }

    @Test
    void cancelOfAFinishedRequestDoesNotTouchTheRequestThatRunsNow() {
        var first = executeCancelledBy("s1", "5", "99");
        var second = new AtomicReference<RunCancellation>();
        when(service.execute(any(), any(), any())).thenAnswer(call -> {
            second.set(call.getArgument(2));
            cancel("s1", "5");
            return ScriptResult.failed("x");
        });

        send("s1", "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"execute\",\"arguments\":{\"code\":\"1\"}}}");

        assertThat(first.isCancelled()).isFalse();
        assertThat(second.get().isCancelled()).isFalse();
    }

    @Test
    void initializeNamesTheCodeModeServer() {
        var result = send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").path("result");

        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("astrolabe-code");
        assertThat(result.path("capabilities").has("tools")).isTrue();
    }

    @Test
    void toolsListHasTheThreeToolsAndExecuteExplainsHowToWriteScripts() {
        var tools = send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");

        assertThat(tools.valueStream().map(tool -> tool.path("name").asString())).containsExactly("list_servers", "search_tools", "execute");
        var description = tools.get(2).path("description").asString();
        assertThat(description).contains("JavaScript").contains("not TypeScript").contains("await servers.<server>.<tool>")
                .contains("{type:'image', mimeType, path}").contains("small");
    }

    @Test
    void codeModeToolsAreNotMcpToolsSoTheProjectServerNeverListsThem() {
        assertThat(McpTool.class.isAssignableFrom(ExecuteTool.class)).isFalse();
        assertThat(McpTool.class.isAssignableFrom(SearchToolsTool.class)).isFalse();
        assertThat(McpTool.class.isAssignableFrom(ListServersTool.class)).isFalse();
        assertThat(McpEndpoint.SERVER_NAME).isNotEqualTo(CodeModeEndpoint.SERVER_NAME);
    }

    @Test
    void listServersReportsEveryServerWithItsState() {
        when(service.listServers(SESSION)).thenReturn(List.of(new ServerStatus("a", "a", "user", "stdio", true, Optional.empty()),
                new ServerStatus("b", "plugin_x_b", "plugin", "http", false, Optional.of("needs sign-in"))));

        var result = callTool("list_servers", "{}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).startsWith("a (user, stdio): connected\nb (saved as plugin_x_b, plugin, http): unavailable, needs sign-in\n")
                .contains("servers.<alias>.<tool>");
    }

    @Test
    void searchToolsPassesTheServerFilter() {
        when(service.searchTools(SESSION, "", "plugin_x_b")).thenReturn("filtered");

        assertThat(text(callTool("search_tools", "{\"server\":\"plugin_x_b\"}"))).isEqualTo("filtered");
    }

    @Test
    void initializeInstructionsNameTheSessionsServersAndTheWayToUseThem() {
        when(connections.aliases(SESSION)).thenReturn(List.of("playwright", "evil\u202e\nname"));

        var text = send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").path("result").path("instructions").asString();

        assertThat(text).contains("NOT available directly: playwright, evil name.").contains("list_servers").contains("search_tools")
                .contains("await servers.<alias>.<tool>(args)").contains("ONE execute").contains("{type:'image', path}").contains("mcp__<plugin>_<server>__*")
                .doesNotContain("\u202e").doesNotContain("evil\n");
    }

    @Test
    void initializeInstructionsCapTheServerList() {
        when(connections.aliases(SESSION)).thenReturn(java.util.stream.IntStream.range(0, 30).mapToObj(i -> "s" + i).toList());

        var text = send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").path("result").path("instructions").asString();

        assertThat(text).contains("s19").doesNotContain("s20,").contains("and 10 more");
    }

    @Test
    void initializeInstructionsFallBackToTheGenericTextWithoutServersOrForAnEndedSession() {
        when(connections.aliases(SESSION)).thenReturn(List.of());
        assertThat(send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").path("result").path("instructions").asString())
                .isEqualTo(CodeModeEndpoint.GENERIC_INSTRUCTIONS);

        when(connections.aliases(SESSION)).thenThrow(new IllegalStateException("The session has ended"));
        assertThat(send("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").path("result").path("instructions").asString())
                .isEqualTo(CodeModeEndpoint.GENERIC_INSTRUCTIONS);
    }

    @Test
    void searchToolsPassesTheQuery() {
        when(service.searchTools(SESSION, "issue", "")).thenReturn("servers.a.issue(): Promise<unknown>");

        assertThat(text(callTool("search_tools", "{\"query\":\" issue \"}"))).isEqualTo("servers.a.issue(): Promise<unknown>");
    }

    @Test
    void executeReturnsTheOutput() {
        when(service.execute(eq(SESSION), eq("return 1"), any())).thenReturn(ScriptResult.ok("1", false));

        assertThat(text(callTool("execute", "{\"code\":\"return 1\"}"))).isEqualTo("1");
    }

    @Test
    void executeFailureKeepsOutputAndErrorAndIsFlagged() {
        when(service.execute(eq(SESSION), any(), any())).thenReturn(ScriptResult.failed("boom", "printed"));

        var result = callTool("execute", "{\"code\":\"x\"}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("printed").contains("Error: boom");
    }

    @Test
    void executeWithoutCodeIsRejected() {
        var result = callTool("execute", "{}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("'code' is required");
    }

    @Test
    void serviceRefusalReachesClaudeAsToolError() {
        when(service.execute(eq(SESSION), any(), any())).thenThrow(new IllegalStateException("docker is not installed"));

        var result = callTool("execute", "{\"code\":\"x\"}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).isEqualTo("docker is not installed");
    }

    @Test
    void unknownSessionIsAToolError() {
        var response = send("other", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_servers\",\"arguments\":{}}}");

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(response.path("result"))).contains("not managed by astrolabe");
    }
}
