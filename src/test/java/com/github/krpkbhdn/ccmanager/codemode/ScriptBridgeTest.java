package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.ccmanager.codemode.policy.Decision;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What a script reaches through the bridge: names, tool results, denials, failures and images. */
class ScriptBridgeTest extends CodeModeFixture {

    @Test
    void aScriptCallToAnUnknownServerNamesTheAvailableOnes() {
        script.set((code, bridge, session) -> {
            try {
                return ScriptResult.ok(bridge.callTool("nosuch", "x", "{}"), false);
            } catch (RuntimeException e) {
                return ScriptResult.failed(e.getMessage());
            }
        });

        assertThat(service.execute(SESSION, "code").error()).hasValueSatisfying(error -> assertThat(error).contains("Unknown server 'nosuch'. Available in Code Mode: alpha, beta. Use list_servers."));
    }

    @Test
    void aScriptCallToAnUnknownToolSuggestsTheClosestNames() {
        when(alpha.listTools()).thenReturn(List.of(tool("alpha", "browser_navigate"), tool("alpha", "browser_click"), tool("alpha", "other")));
        script.set((code, bridge, session) -> {
            try {
                return ScriptResult.ok(bridge.callTool("alpha", "navigate", "{}"), false);
            } catch (RuntimeException e) {
                return ScriptResult.failed(e.getMessage());
            }
        });

        assertThat(service.execute(SESSION, "code").error()).hasValue("Unknown tool 'navigate' on server 'alpha'. Did you mean: browser_navigate. Use search_tools.");
    }

    @Test
    void theBridgeHandsTheScriptTheServerIndexAndAcceptsCanonicalNames() {
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));
        var index = new AtomicReference<String>();
        script.set((code, bridge, session) -> {
            index.set(bridge.serverIndexJson());
            return ScriptResult.ok(bridge.callTool("mcp__alpha__echo", "echo", "{}"), false);
        });

        assertThat(service.execute(SESSION, "code").output()).isEqualTo("\"ok\"");
        assertThat(index.get()).contains("\"alpha\":\"alpha\"").contains("\"available\":\"alpha, beta\"");
    }

    @Test
    void stuckToolCallFailsAfterItsOwnTimeoutAndResetsTheServerClient() {
        var stuck = new java.util.concurrent.CountDownLatch(1);
        when(alpha.callTool(any(), any())).thenAnswer(call -> {
            stuck.await();
            return json("{}");
        });
        scriptCallingAlphaAndReportingErrors();
        var quick = TestProperties.of(Duration.ofSeconds(30), 100_000, 20_000, 10, Duration.ofMinutes(10), "node:22-alpine", "256m", 1,
                Duration.ofDays(30), Duration.ofMillis(200));

        var result = serviceWith(quick).execute(SESSION, "code");

        assertThat(result.error()).hasValueSatisfying(error -> assertThat(error).contains("alpha.echo timed out").contains("reset"));
        verify(alpha).close();
        stuck.countDown();
    }

    @Test
    void textThatIsNotJsonStaysAString() {
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[{\"type\":\"text\",\"text\":\"12 apples\"}]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));

        assertThat(service.execute(SESSION, "code").output()).isEqualTo("\"12 apples\"");
    }

    @Test
    void structuredContentWinsOverText() {
        when(alpha.callTool(any(), any())).thenReturn(json("{\"structuredContent\":{\"a\":2},\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));

        assertThat(service.execute(SESSION, "code").output()).isEqualTo("{\"a\":2}");
    }

    @Test
    void deniedCallNeverReachesTheServerAndBecomesAScriptError() {
        when(gate.decide(any(), any(), any(), any())).thenReturn(Decision.denied("Denied by the user in cc-manager"));
        scriptCallingAlphaAndReportingErrors();

        var result = service.execute(SESSION, "code");

        assertThat(result.error()).contains("Denied by the user in cc-manager");
        verify(alpha, never()).callTool(any(), any());
        verify(audit).record(any(), any(), any(), org.mockito.ArgumentMatchers.eq(Decision.Kind.DENIED),
                org.mockito.ArgumentMatchers.eq(CodeModeAudit.Status.ERROR), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void imagesAreSavedAsFilesAndReplacedByTheirPath() throws Exception {
        var data = Base64.getEncoder().encodeToString(PNG);
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + data
                + "\"},{\"type\":\"image\",\"mimeType\":\"image/svg+xml\",\"data\":\"" + data + "\"}]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));

        var parts = json(service.execute(SESSION, "code").output());

        var file = dataDir.resolve("code-mode").resolve("s1").resolve("img-1.png");
        assertThat(parts.get(0).path("type").asString()).isEqualTo("image");
        assertThat(parts.get(0).path("path").asString()).isEqualTo(file.toAbsolutePath().toString());
        assertThat(Files.readAllBytes(file)).isEqualTo(PNG);
        assertThat(parts.get(1).path("omitted").asString()).isEqualTo("unsupported image type");
        assertThat(parts.get(1).has("path")).isFalse();
    }

    @Test
    void serverErrorAnswerKeepsTheConnectionAndPassesItsMessageToTheScript() {
        when(alpha.callTool(any(), any())).thenThrow(new com.github.krpkbhdn.ccmanager.codemode.client.McpServerErrorException(-32602, "bad argument x"));
        scriptCallingAlphaAndReportingErrors();

        var result = service.execute(SESSION, "code");

        assertThat(result.error()).hasValueSatisfying(error -> assertThat(error).contains("bad argument x"));
        verify(alpha, never()).close();
        verify(clients, times(1)).create(argThat("alpha"));
    }

    @Test
    void callFromAnAbandonedScriptAfterItEndedIsRefused() {
        var bridge = new AtomicReference<HostBridge>();
        script.set((code, host, session) -> {
            bridge.set(host);
            return ScriptResult.ok("done", false);
        });
        service.execute(SESSION, "code");

        assertThatThrownBy(() -> bridge.get().callTool("alpha", "echo", "{}")).hasMessage(ScriptBridge.CANCELLED);
        verify(alpha, never()).callTool(any(), any());
    }

    @Test
    void clientThatFailedACallIsDroppedAndTheNextCallReconnects() {
        when(alpha.callTool(any(), any())).thenThrow(new com.github.krpkbhdn.ccmanager.codemode.client.McpClientException("The server alpha ended"));
        scriptCallingAlphaAndReportingErrors();

        service.execute(SESSION, "code");
        service.execute(SESSION, "code");

        verify(alpha, times(2)).close();
        verify(clients, times(2)).create(argThat("alpha"));
    }
}
