package com.github.krpkbhdn.ccmanager.codemode.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.TestProperties;
import com.github.krpkbhdn.ccmanager.codemode.HostBridge;
import com.github.krpkbhdn.ccmanager.codemode.ScriptResult;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class GraalJsSandboxTest {

    private static final SessionContext SESSION = new SessionContext(1, "s1");
    private static final GraalJsSandbox SANDBOX = sandbox(Duration.ofSeconds(10), 100_000, 20_000);

    private final List<String> hostLogs = new ArrayList<>();
    private final HostBridge bridge = new HostBridge() {
        @Override
        public String callTool(String server, String tool, String argsJson) {
            if (tool.equals("boom")) {
                throw new IllegalStateException("denied by policy");
            }
            return "{\"server\":\"" + server + "\",\"tool\":\"" + tool + "\",\"args\":" + argsJson + "}";
        }

        @Override
        public void log(String line) {
            hostLogs.add(line);
        }
    };

    @AfterAll
    static void closeEngine() {
        SANDBOX.close();
    }

    private static GraalJsSandbox sandbox(Duration timeout, long statementLimit, int maxOutputChars) {
        return new GraalJsSandbox(TestProperties.of(timeout, statementLimit, maxOutputChars, 10, Duration.ofMinutes(10),
                "node:22-alpine", "256m", 1, Duration.ofDays(30), Duration.ofSeconds(25)));
    }

    private ScriptResult run(String code) {
        return SANDBOX.run(code, bridge, SESSION);
    }

    @Test
    void awaitsAToolCallAndReturnsTheObject() {
        ScriptResult result = run("const r = await servers.a.b({x: 1}); return {tool: r.tool, x: r.args.x};");

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("{\"tool\":\"b\",\"x\":1}");
    }

    @Test
    void supportsBracketAccessForServerNamesWithDashes() {
        ScriptResult result = run("return (await servers['my-server']['do-it']({})).server;");

        assertThat(result.output()).isEqualTo("my-server");
    }

    @Test
    void promiseAllRunsParallelCalls() {
        ScriptResult result = run("const rs = await Promise.all([servers.a.one({}), servers.a.two({})]); return rs.map(r => r.tool);");

        assertThat(result.output()).isEqualTo("[\"one\",\"two\"]");
    }

    @Test
    void undefinedReturnGivesOnlyTheLogLines() {
        ScriptResult result = run("console.log('a', 1); console.warn({k: 2});");

        assertThat(result.output()).isEqualTo("a 1\n{\"k\":2}");
        assertThat(hostLogs).containsExactly("a 1", "{\"k\":2}");
    }

    @Test
    void logLinesComeBeforeTheReturnValue() {
        ScriptResult result = run("console.info('first'); return 5;");

        assertThat(result.output()).isEqualTo("first\n5");
    }

    @Test
    void thrownErrorFailsTheScript() {
        ScriptResult result = run("throw new Error('nope');");

        assertThat(result.error()).hasValueSatisfying(message -> assertThat(message).contains("nope"));
    }

    @Test
    void hostCallErrorBecomesARejectionTheScriptCanCatch() {
        ScriptResult result = run("try { await servers.a.boom({}); } catch (e) { return 'caught ' + e.message; }");

        assertThat(result.output()).contains("caught").contains("denied by policy");
    }

    @Test
    void uncaughtHostCallErrorFailsTheScript() {
        ScriptResult result = run("await servers.a.boom({});");

        assertThat(result.error()).hasValueSatisfying(message -> assertThat(message).contains("denied by policy"));
    }

    @Test
    void awaitingOrReturningAServerObjectDoesNotCallAToolNamedThen() {
        ScriptResult result = run("const s = await servers.a; const t = await servers.a.b; return [typeof s, typeof t, typeof servers.then, typeof servers.a.then,"
                + " typeof servers.a[Symbol.iterator], typeof servers.a.b];");

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("[\"object\",\"function\",\"undefined\",\"undefined\",\"undefined\",\"function\"]");
    }

    @Test
    void stringConversionAndProbesOnServerObjectsDoNotCallATool() {
        ScriptResult result = run("const p = [String(servers), `${servers.a}`, String(servers.a), servers.a + '', typeof servers.a.valueOf, typeof servers.a.constructor, typeof servers.a.inspect, typeof servers.a[Symbol.toPrimitive]].join('|'); return p + '|' + (await servers.a.__call('toString', {})).tool;");

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("[object Object]|[object Object]|[object Object]|[object Object]|function|function|undefined|undefined|toString");
    }

    @Test
    void aToolNamedThenIsReachedThroughCall() {
        ScriptResult result = run("return (await servers.a.__call('then', {x: 1})).tool;");

        assertThat(result.output()).isEqualTo("then");
    }

    @Test
    void printLoadAndTheFinisherAreNotReachableAndNothingGoesToTheProcessStreams() {
        var realOut = System.out;
        var realErr = System.err;
        var captured = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(captured));
        System.setErr(new java.io.PrintStream(captured));
        ScriptResult result;
        try {
            result = run("const r = [typeof print, typeof printErr, typeof load, typeof loadWithNewGlobal, typeof __finish, typeof Java, typeof Polyglot];"
                    + " try { print('x'); } catch (e) {} try { printErr('y'); } catch (e) {} try { console.log('z'); } catch (e) {}"
                    + " try { load('nashorn:x'); } catch (e) {} return r;");
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }

        assertThat(result.output()).contains("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\"]");
        assertThat(captured.toString()).isEmpty();
    }

    @Test
    void javaAndNodeStyleEscapesAreNotAvailable() {
        ScriptResult result = run("return [typeof Java, typeof Packages, typeof Graal, typeof load, typeof process, typeof require,"
                + " typeof fetch, typeof __bridge, typeof Polyglot, typeof WebAssembly === 'undefined' || 'wasm'];");

        assertThat(result.output()).startsWith("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\","
                + "\"undefined\",\"undefined\",\"undefined\",\"undefined\"");
    }

    @Test
    void javaTypeCallFails() {
        assertThat(run("return Java.type('java.lang.System').getProperty('user.home');").error()).isPresent();
    }

    @Test
    void syncInfiniteLoopHitsTheStatementLimit() {
        ScriptResult result = run("while (true) {}");

        assertThat(result.error()).contains("statement limit exceeded");
    }

    @Test
    void asyncInfiniteLoopIsStopped() {
        ScriptResult result = run("for (;;) { await null; }");

        assertThat(result.error()).isPresent();
    }

    @Test
    void watchdogStopsAScriptThatStaysUnderTheStatementLimit() {
        try (GraalJsSandbox slow = sandbox(Duration.ofMillis(300), Long.MAX_VALUE, 1000)) {
            ScriptResult result = slow.run("const f = () => Promise.resolve().then(f); f(); await new Promise(() => {});", bridge, SESSION);

            assertThat(result.error()).isPresent();
        }
    }

    @Test
    void watchdogReportsATimeout() {
        try (GraalJsSandbox slow = sandbox(Duration.ofMillis(300), Long.MAX_VALUE, 1000)) {
            ScriptResult result = slow.run("while (true) {}", bridge, SESSION);

            assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
        }
    }

    @Test
    void longOutputKeepsHeadAndTailWithAMarker() {
        try (GraalJsSandbox small = sandbox(Duration.ofSeconds(10), 100_000, 100)) {
            ScriptResult result = small.run("return 'a'.repeat(150) + 'b'.repeat(150);", bridge, SESSION);

            assertThat(result.truncated()).isTrue();
            assertThat(result.output()).startsWith("aaa").endsWith("bbb").contains("[truncated 200 chars]");
        }
    }

    @Test
    void failureKeepsTheLinesPrintedBefore() {
        ScriptResult result = run("console.log('before'); throw new Error('nope');");

        assertThat(result.error()).hasValueSatisfying(message -> assertThat(message).contains("nope"));
        assertThat(result.output()).isEqualTo("before");
    }

    @Test
    void statementLimitKeepsTheLinesPrintedBefore() {
        ScriptResult result = run("console.log('before'); while (true) {}");

        assertThat(result.error()).contains("statement limit exceeded");
        assertThat(result.output()).isEqualTo("before");
    }

    @Test
    void logFloodKeepsHeadAndTailWithinTheLimit() {
        try (GraalJsSandbox small = sandbox(Duration.ofSeconds(10), 10_000_000, 100)) {
            ScriptResult result = small.run("for (let i = 0; i < 5000; i++) console.log('line-' + i + '-' + 'z'.repeat(50));"
                    + " throw new Error('end');", bridge, SESSION);

            assertThat(result.error()).isPresent();
            assertThat(result.output()).hasSizeLessThan(200).startsWith("line-0-").contains("[truncated").endsWith("z".repeat(50));
        }
    }

    @Test
    void timeoutHoldsWhileAnInterruptibleHostCallBlocks() throws InterruptedException {
        InterruptibleBlockingBridge blocking = new InterruptibleBlockingBridge();
        try (GraalJsSandbox slow = sandbox(Duration.ofMillis(300), Long.MAX_VALUE, 1000)) {
            ScriptResult result = slow.run("console.log('before'); await servers.a.b({});", blocking, SESSION);

            assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
            assertThat(result.output()).isEqualTo("before");
            assertThat(blocking.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutHoldsWhileAHostCallIgnoresInterrupts() {
        CountDownLatch release = new CountDownLatch(1);
        HostBridge stubborn = new HostBridge() {
            @Override
            public String callTool(String server, String tool, String argsJson) {
                boolean done = false;
                while (!done) {
                    try {
                        done = release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        // keeps blocking, like uninterruptible IO
                    }
                }
                return "{}";
            }

            @Override
            public void log(String line) {
            }
        };
        try (GraalJsSandbox slow = sandbox(Duration.ofMillis(300), Long.MAX_VALUE, 1000)) {
            long start = System.nanoTime();
            ScriptResult result = slow.run("await servers.a.b({});", stubborn, SESSION);

            release.countDown();
            assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void stringsAreShownRawAndOtherValuesAsJson() {
        assertThat(run("return 'x';").output()).isEqualTo("x");
        assertThat(run("return null;").output()).isEqualTo("null");
        assertThat(run("return [1, 'a'];").output()).isEqualTo("[1,\"a\"]");
        assertThat(run("console.log('s', undefined, 1);").output()).isEqualTo("s undefined 1");
    }

    @Test
    void circularAndBigIntValuesFallBackInsteadOfThrowing() {
        ScriptResult result = run("const a = {}; a.self = a; console.log(a, 10n); return a;");

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("[object Object] 10\n[object Object]");
    }

    @Test
    void serverObjectsSerialiseWithoutARemoteCall() {
        ScriptResult result = run("console.log(servers.a); return [JSON.stringify(servers.a), typeof servers.a.toJSON, (await servers.a.__call('toJSON', {})).tool];");

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("{}\n[\"{}\",\"undefined\",\"toJSON\"]");
    }

    @Test
    void runawayRegexIsCancelledAndItsWorkerEnds() throws InterruptedException {
        ScriptResult result = sandbox(Duration.ofSeconds(1), 100_000, 1000).run("return /^(.*?,){11}P/.test('1,'.repeat(200000));", bridge, SESSION);
        long deadline = System.currentTimeMillis() + 10_000;
        while (scriptWorkerAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
        assertThat(scriptWorkerAlive()).isFalse();
    }

    @Test
    void atomicsWaitIsRefused() {
        assertThat(run("Atomics.wait(new Int32Array(new SharedArrayBuffer(16)), 0, 0); return 1;").error()).isPresent();
    }

    private static boolean scriptWorkerAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> "code-mode-script".equals(thread.getName()) && thread.isAlive());
    }

    @Test
    void neverSettlingPromiseIsAnError() {
        ScriptResult result = run("await new Promise(() => {});");

        assertThat(result.error()).hasValueSatisfying(message -> assertThat(message).contains("did not finish"));
    }

    @Test
    void contextsDoNotShareGlobals() {
        run("globalThis.leak = 1;");

        assertThat(run("return typeof leak;").output()).isEqualTo("undefined");
    }
}
