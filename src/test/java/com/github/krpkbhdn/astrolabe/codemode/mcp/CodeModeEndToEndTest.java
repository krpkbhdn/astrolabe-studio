package com.github.krpkbhdn.astrolabe.codemode.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalAnswer;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector.Availability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/** Code Mode from the JSON-RPC endpoint down to a real GraalJS script talking to a real stdio MCP server (a small Node process). */
class CodeModeEndToEndTest extends EndToEndFixture {

    @Test
    void searchToolsReturnsTypeScriptSignaturesAndListServersConnects() {
        assertThat(text(call("s1", "list_servers", Map.of()))).contains("fake (user, stdio)");
        var signatures = text(call("s1", "search_tools", Map.of("query", "add")));

        assertThat(signatures).contains("servers.fake.add(").contains("a: number").contains("Promise");
    }

    @Test
    void oneScriptChainsSeveralCallsAndReturnsOnlyASmallResult() {
        var result = execute("const x = await servers.fake.add({a: 1, b: 2}); const y = await servers.fake.add({a: x.sum, b: 10});"
                + " const z = await servers.fake.add({a: y.sum, b: 100}); console.log('steps', x.sum, y.sum); return z;");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).contains("steps 3 13").contains("{\"sum\":113}");
    }

    @Test
    void imageResultIsStoredAsAFilePath() throws Exception {
        var result = execute("return await servers.fake.shot({});");

        var image = mapper.readTree(text(result));
        assertThat(image.path("type").asString()).isEqualTo("image");
        assertThat(Files.readAllBytes(Path.of(image.path("path").asString()))).startsWith((byte) 0x89, 'P', 'N', 'G');
        assertThat(text(result)).doesNotContain("iVBO");
    }

    @Test
    void readOnlyToolWithOnlyTheReadOnlyHintRunsWithoutAsking() {
        var asked = answerQuestions(proposal -> new ProposalAnswer.Denial("no"));

        var result = execute("return await servers.fake.peek({});");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).contains("peeked");
        assertThat(asked).isEmpty();
    }

    @Test
    void nonReadOnlyCallAsksAndAnAllowRunsIt() {
        var asked = answerQuestions(proposal -> ProposalAnswer.PermissionAllow.once());

        var result = execute("return await servers.fake.write_file({path: '/tmp/x', token: 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789'});");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).contains("written /tmp/x");
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).contains("fake.write_file").doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    void deniedCallBecomesAScriptErrorTheScriptCanCatchOrFailOn() {
        var asked = answerQuestions(proposal -> new ProposalAnswer.Denial("no"));

        var failed = execute("return await servers.fake.write_file({path: 'x'});");
        var caught = execute("try { await servers.fake.write_file({path: 'x'}); } catch (e) { return 'caught: ' + e.message; }");

        assertThat(failed.path("isError").asBoolean()).isTrue();
        assertThat(text(failed)).contains("Denied");
        assertThat(text(caught)).contains("caught").contains("Denied");
        assertThat(asked).hasSize(2);
    }

    @Test
    void toolErrorResultThrowsInTheScriptAndKeepsTheConnection() {
        var caught = execute("await servers.fake.count({}); try { await servers.fake.fail({}); } catch (e) { console.log('caught: ' + e.message); } return await servers.fake.count({});");
        var uncaught = execute("return await servers.fake.fail({});");

        assertThat(text(caught)).contains("caught: ").contains("quota exceeded").endsWith("2");
        assertThat(uncaught.path("isError").asBoolean()).isTrue();
        assertThat(text(uncaught)).contains("quota exceeded");
        assertThat(text(execute("return await servers.fake.count({});"))).isEqualTo("3");
    }

    @Test
    void sessionGrantAvoidsAskingAgain() {
        var asked = answerQuestions(proposal -> new ProposalAnswer.PermissionAllow(proposal.suggestions().stream().map(s -> s.raw()).toList()));

        execute("await servers.fake.write_file({path: 'a'});");
        var second = execute("return await servers.fake.write_file({path: 'b'});");

        assertThat(text(second)).contains("written b");
        assertThat(asked).hasSize(1);
    }

    @Test
    void infiniteLoopAndHugeOutputAreBounded() {
        var loop = execute("while (true) {}");
        var big = execute("return 'x'.repeat(5000000);");
        var logs = execute("for (let i = 0; i < 100000; i++) console.log('line ' + i + ' xxxxxxxxxxxxxxxxxxxx'); return 1;");
        var bigFromServer = execute("const r = await servers.fake.big({}); return r;");

        assertThat(loop.path("isError").asBoolean()).isTrue();
        assertThat(text(big).length()).isLessThan(25_000);
        assertThat(text(logs).length()).isLessThan(25_000);
        assertThat(text(bigFromServer).length()).isLessThan(25_000);
    }

    @Test
    void hostEscapesAreNotReachable() {
        for (var snippet : List.of("return Java.type('java.lang.System').getProperty('user.home');", "return process.env.PATH;",
                "return require('fs').readdirSync('.');", "return await fetch('http://127.0.0.1:1/');", "return java.lang.System.getenv();",
                "return Packages.java.lang.System.getenv();", "return new (Object.getPrototypeOf(async function(){}).constructor)('return process')();")) {
            var result = execute(snippet);

            assertThat(result.path("isError").asBoolean()).as(snippet + " -> " + text(result)).isTrue();
            assertThat(text(result)).as(snippet).doesNotContain("Users");
        }
        var types = text(execute("return typeof Graal + typeof Polyglot + typeof load + typeof XMLHttpRequest + typeof WebSocket + typeof Java;"));
        assertThat(types).isEqualTo("undefinedundefinedundefinedundefinedundefinedundefined");
    }

    @Test
    void projectWithCodeModeOffCannotExecute() {
        var disabled = call("s2", "execute", Map.of("code", "return 1"));

        assertThat(disabled.path("isError").asBoolean()).isTrue();
        assertThat(text(disabled)).contains("not enabled");
    }

    @Test
    void unavailableContainerBackendFailsWithItsReasonAndDoesNotFallBack() {
        when(isolation.get()).thenReturn(IsolationBackend.DOCKER);
        when(detector.availability(IsolationBackend.DOCKER)).thenReturn(new Availability(false, "docker is not installed or not on the PATH"));

        var result = execute("return 1");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("docker is not installed");
        verify(sandboxes, never()).forBackend(any());
    }

    @Test
    void missingImageFailsAndIsNeverPulled() {
        when(isolation.get()).thenReturn(IsolationBackend.PODMAN);
        when(detector.availability(IsolationBackend.PODMAN)).thenReturn(new Availability(true, ""));
        when(detector.imagePresent(any(), any())).thenReturn(false);

        var result = execute("return 1");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("image");
        verify(detector, never()).pullImage(any(), any());
        verify(sandboxes, never()).forBackend(any());
    }

    @Test
    void auditReceivesServerAndToolOnly() {
        execute("return await servers.fake.add({a: 123456, b: 654321});");

        verify(audit).record(any(), ArgumentMatchers.eq("fake"), ArgumentMatchers.eq("add"), any(), any(), ArgumentMatchers.anyLong());
    }
}
