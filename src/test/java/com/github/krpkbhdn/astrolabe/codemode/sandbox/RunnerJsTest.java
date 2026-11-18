package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.TestProcesses;
import com.github.krpkbhdn.astrolabe.workspace.ProcessTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Runs the container runner on the host's node over the line protocol, so the servers proxy semantics match the GraalJS prelude; skipped without node. */
class RunnerJsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Process node() throws IOException {
        try {
            var runner = temp.resolve("runner.js");
            try (var stream = RunnerJsTest.class.getResourceAsStream("/code-mode/runner.js")) {
                Files.write(runner, stream.readAllBytes());
            }
            return new ProcessBuilder("node", runner.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            assumeTrue(false, "node is not installed");
            throw e;
        }
    }

    private static String exchange(Process process, String code, String callReply) throws IOException {
        var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var out = process.getOutputStream();
        out.write((MAPPER.createObjectNode().put("type", "run").put("code", code) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String line;
        while ((line = TestProcesses.readLine(in)) != null) {
            var message = MAPPER.readTree(line);
            if ("call".equals(message.path("type").asString())) {
                out.write((MAPPER.createObjectNode().put("type", "result").put("id", message.path("id").asInt()).put("value", callReply
                        .replace("TOOL", message.path("tool").asString())) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else if (!"log".equals(message.path("type").asString())) {
                return line;
            }
        }
        return "";
    }

    @Test
    void awaitingAServerObjectDoesNotCallAToolNamedThen() throws Exception {
        var process = node();
        try {
            var done = exchange(process, "const s = await servers.a; return [typeof s, typeof servers.then, typeof servers.a.then, typeof servers.a.b];", "{}");

            assertThat(done).isEqualTo("{\"type\":\"done\",\"value\":\"[\\\"object\\\",\\\"undefined\\\",\\\"undefined\\\",\\\"function\\\"]\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    @Test
    void stringConversionAndProbesOnServerObjectsDoNotCallATool() throws Exception {
        var process = node();
        try {
            var done = exchange(process, "const p = [String(servers), `${servers.a}`, String(servers.a), servers.a + '', typeof servers.a.valueOf, typeof servers.a.constructor, typeof servers.a.inspect, typeof servers.a[Symbol.toPrimitive]].join('|'); return p + '|' + (await servers.a.__call('toString', {})).tool;", "{\"tool\":\"TOOL\"}");

            assertThat(done).isEqualTo("{\"type\":\"done\",\"value\":\"[object Object]|[object Object]|[object Object]|[object Object]|function|function|undefined|undefined|toString\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    @Test
    void aToolNamedThenIsReachedThroughCall() throws Exception {
        var process = node();
        try {
            var done = exchange(process, "return (await servers.a.__call('then', {})).tool;", "{\"tool\":\"TOOL\"}");

            assertThat(done).isEqualTo("{\"type\":\"done\",\"value\":\"then\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    @Test
    void valuesFollowTheSameRuleAsThePrelude() throws Exception {
        var process = node();
        try {
            assertThat(exchange(process, "return 'x';", "{}")).isEqualTo("{\"type\":\"done\",\"value\":\"x\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
        process = node();
        try {
            assertThat(exchange(process, "return [1, 'a'];", "{}")).isEqualTo("{\"type\":\"done\",\"value\":\"[1,\\\"a\\\"]\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
        process = node();
        try {
            assertThat(exchange(process, "return undefined;", "{}")).isEqualTo("{\"type\":\"done\",\"value\":\"\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    private String valueOf(String script) throws Exception {
        var process = node();
        try {
            var done = MAPPER.readTree(exchange(process, script, "{}"));
            return done.path("value").asString(done.path("message").asString());
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    @Test
    void requireProcessAndEveryRouteToTheRealFunctionConstructorAreUnreachable() throws Exception {
        var probes = String.join(" ", "const r = [typeof require, typeof process, typeof module, typeof globalThis.process];",
                "for (const probe of [() => servers.constructor.constructor('return process')(), () => this.constructor.constructor('return process')(),",
                "() => servers.a.b({}).constructor.constructor('return process')(), () => console.log.constructor('return process')(),",
                "() => new Error('x').constructor.constructor('return process')(),",
                "() => Object.getPrototypeOf(servers).constructor('return process')(), () => servers.a.__call.constructor('return process')()]) {",
                "try { r.push(typeof probe()); } catch (e) { r.push('blocked'); } } return r.join(',');");

        assertThat(valueOf(probes)).isEqualTo("undefined,undefined,undefined,undefined,blocked,blocked,blocked,blocked,blocked,blocked,blocked");
    }

    @Test
    void aToolErrorReachesTheScriptAsAnErrorOfTheScriptRealm() throws Exception {
        var process = node();
        try {
            var out = process.getOutputStream();
            var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var code = "try { await servers.a.b({}); } catch (e) { return [e.message, typeof e.constructor.constructor('return typeof process')()].join('|'); }";
            out.write((MAPPER.createObjectNode().put("type", "run").put("code", code) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            var call = MAPPER.readTree(TestProcesses.readLine(in));
            out.write((MAPPER.createObjectNode().put("type", "result").put("id", call.path("id").asInt()).put("error", "denied") + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertThat(MAPPER.readTree(TestProcesses.readLine(in)).path("value").asString()).isEqualTo("denied|string");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }

    @Test
    void anUnsettledPromiseFailsAtOnceAndAnErrorUsesTheGraalWording() throws Exception {
        assertThat(valueOf("await new Promise(() => {});")).isEqualTo("script did not finish: a promise never settled");
        assertThat(valueOf("throw new Error('bad');")).isEqualTo("Error: bad");
        assertThat(valueOf("throw 'plain';")).isEqualTo("plain");
    }

    @Test
    void timersRunInDueOrderClearedOnesDoNotAndAPendingTimerIsNotStuck() throws Exception {
        assertThat(valueOf("const seen = []; setTimeout(() => seen.push('late'), 30); const gone = setTimeout(() => seen.push('gone'), 5); clearTimeout(gone);"
                + " await new Promise(r => setTimeout(r, 10)); seen.push('slept'); await new Promise(r => setTimeout(r, 60)); return seen.join();")).isEqualTo("slept,late");
        assertThat(valueOf("setTimeout(() => {}, 1); await new Promise(() => {});")).isEqualTo("script did not finish: a promise never settled");
        assertThat(valueOf("const t = setTimeout(() => {}, 100000); clearTimeout(t); await new Promise(() => {});")).isEqualTo("script did not finish: a promise never settled");
        assertThat(valueOf("await new Promise(r => setTimeout(r, Infinity > 0 ? 0.5 : 1)); await new Promise(r => setTimeout(r, NaN)); return 'ok';")).isEqualTo("ok");
        assertThat(valueOf("setTimeout(() => { throw new Error('late boom'); }, 1); await new Promise(r => setTimeout(r, 50));")).isEqualTo("Error: late boom");
    }

    @Test
    void circularAndBigIntValuesFallBackAndServerObjectsStringifyWithoutACall() throws Exception {
        var process = node();
        try {
            var done = exchange(process, "const a = {}; a.self = a; console.log(a, 10n); return [JSON.stringify(servers.a), typeof servers.a.toJSON, String(a)].join('|');", "{}");

            assertThat(done).isEqualTo("{\"type\":\"done\",\"value\":\"{}|undefined|[object Object]\"}");
        } finally {
            ProcessTree.killAndWait(process, Duration.ofSeconds(5));
        }
    }
}
