package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.codemode.HostBridge;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The same script must give the same output and error text on GraalJS and in the container runner (host node stands in for the container); skipped without node. */
class SandboxParityTest {

    private static final SessionContext SESSION = new SessionContext(1, "s");
    private static final List<String> SCRIPTS = List.of(
            "return 'x';",
            "return undefined;",
            "console.log('a'); return 'b';",
            "console.log('only');",
            "console.log(''); console.log('x'); return 'r';",
            "console.log(''); return '';",
            "return {a: [1, 'two']};",
            "const a = {}; a.self = a; console.log(a, 10n); return 1;",
            "const r = await servers.s.t({x: 1}); return r.tool + r.args.x;",
            "try { await servers.s.boom({}); } catch (e) { return e.message; }",
            "throw new Error('bad');",
            "console.log('before'); throw new Error('bad');",
            "throw 'plain';",
            "await new Promise(() => {});",
            "console.log('hung'); await new Promise(() => {});");

    private static final HostBridge BRIDGE = new HostBridge() {
        @Override
        public String callTool(String server, String tool, String argsJson) {
            if (tool.equals("boom")) {
                throw new IllegalStateException("denied");
            }
            return "{\"tool\":\"" + tool + "\",\"args\":" + argsJson + "}";
        }

        @Override
        public void log(String line) {
        }
    };

    @Test
    void bothBackendsAnswerEveryScriptIdentically() throws IOException {
        assumeTrue(nodeAvailable(), "node is not installed");
        var properties = TestProperties.of(Duration.ofSeconds(20), 1_000_000, 10_000, 10, Duration.ofMinutes(10), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var graal = new GraalJsSandbox(properties);
        var container = new ContainerSandbox(IsolationBackend.DOCKER, properties, JsonMapper.builder().build(), "0123abcd", NodeProcesses::start);
        try {
            for (var script : SCRIPTS) {
                var expected = graal.run(script, BRIDGE, SESSION);
                var actual = container.run(script, BRIDGE, SESSION);

                assertThat(actual.output()).as("output of %s", script).isEqualTo(expected.output());
                assertThat(actual.error()).as("error of %s", script).isEqualTo(expected.error());
            }
        } finally {
            graal.close();
        }
    }

    private static final HostBridge INDEXED = new HostBridge() {
        @Override
        public String callTool(String server, String tool, String argsJson) {
            return "{\"server\":\"" + server + "\"}";
        }

        @Override
        public void log(String line) {
        }

        @Override
        public String serverIndexJson() {
            return "{\"names\":{\"playwright\":\"playwright\",\"plugin_playwright_playwright\":\"playwright\",\"db\":\"db\"},\"available\":\"playwright, db\"}";
        }
    };

    @Test
    void serverSpellingsAndUnknownServersBehaveTheSameInBothBackends() throws IOException {
        assumeTrue(nodeAvailable(), "node is not installed");
        var properties = TestProperties.of(Duration.ofSeconds(20), 1_000_000, 10_000, 10, Duration.ofMinutes(10), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var graal = new GraalJsSandbox(properties);
        var container = new ContainerSandbox(IsolationBackend.DOCKER, properties, JsonMapper.builder().build(), "0123abcd", NodeProcesses::start);
        var expected = List.of(
                "return (await servers.plugin_playwright_playwright.x({})).server;", "playwright",
                "return (await servers.mcp__plugin_playwright_playwright__x.y({})).server;", "playwright",
                "return (await servers['mcp__db__q'].y({})).server;", "db",
                "return (await servers.playwright.__call('then', {})).server;", "playwright",
                "return typeof servers.then;", "undefined",
                "return String(servers.playwright);", "[object Object]",
                "try { servers.nosuch; } catch (e) { return e.message; }", "Unknown server 'nosuch'. Available in Code Mode: playwright, db. Use list_servers.",
                "await servers.nosuch.x({});", "Error: Unknown server 'nosuch'. Available in Code Mode: playwright, db. Use list_servers.");
        try {
            for (int i = 0; i < expected.size(); i += 2) {
                for (var sandbox : List.<ScriptSandbox>of(graal, container)) {
                    var result = sandbox.run(expected.get(i), INDEXED, SESSION);
                    assertThat(result.error().orElse(result.output())).as("%s of %s", sandbox.getClass().getSimpleName(), expected.get(i))
                            .isEqualTo(expected.get(i + 1));
                }
            }
        } finally {
            graal.close();
        }
    }

    private static final HostBridge ALIASED = new HostBridge() {
        @Override
        public String callTool(String server, String tool, String argsJson) {
            return "{\"server\":\"" + server + "\"}";
        }

        @Override
        public void log(String line) {
        }

        @Override
        public String serverIndexJson() {
            return "{\"names\":{\"db\":\"db\",\"my__srv\":\"my__srv\",\"plugin_p_db\":\"db\"},\"available\":\"db, my__srv\"}";
        }
    };

    @Test
    void aliasResolutionGivesTheSameAnswerForEverySpellingInBothBackends() throws IOException {
        assumeTrue(nodeAvailable(), "node is not installed");
        var properties = TestProperties.of(Duration.ofSeconds(20), 1_000_000, 10_000, 10, Duration.ofMinutes(10), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var graal = new GraalJsSandbox(properties);
        var container = new ContainerSandbox(IsolationBackend.DOCKER, properties, JsonMapper.builder().build(), "0123abcd", NodeProcesses::start);
        var spellings = List.of("db", "plugin_p_db", "my__srv", "mcp__db", "mcp__db__", "mcp__db__q__r", "mcp__plugin_p_db__q", "mcp__my__srv__q", "mcp__", "mcp____x",
                "mcp__nosuch__t", "nosuch", "DB", "x".repeat(60));
        try {
            for (var spelling : spellings) {
                var script = "try { return (await servers['" + spelling + "'].x({})).server; } catch (e) { return String(e.message); }";
                var expected = graal.run(script, ALIASED, SESSION);
                var actual = container.run(script, ALIASED, SESSION);

                assertThat(actual.output()).as("output for %s", spelling).isEqualTo(expected.output());
                assertThat(actual.error()).as("error for %s", spelling).isEqualTo(expected.error());
            }
            assertThat(graal.run("return (await servers['mcp__db__q'].x({})).server;", ALIASED, SESSION).output()).isEqualTo("db");
            assertThat(graal.run("return (await servers['mcp__my__srv__q'].x({})).server;", ALIASED, SESSION).error()).isPresent();
        } finally {
            graal.close();
        }
    }

    @Test
    void anUnsettledPromiseFailsAtOnceInTheContainerRunnerToo() throws IOException {
        assumeTrue(nodeAvailable(), "node is not installed");
        var properties = TestProperties.of(Duration.ofSeconds(20), 1_000_000, 10_000, 10, Duration.ofMinutes(10), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var container = new ContainerSandbox(IsolationBackend.DOCKER, properties, JsonMapper.builder().build(), "0123abcd", NodeProcesses::start);

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> container.run("await new Promise(() => {});", BRIDGE, SESSION));

        assertThat(result.error()).hasValue("script did not finish: a promise never settled");
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Starts the host's node with the environment variable and bootstrap that the container command would hand to the image's node. */
    static final class NodeProcesses {

        private NodeProcesses() {}

        static Process start(List<String> command) throws IOException {
            if (command.contains("kill")) {
                return new ProcessBuilder("node", "-e", "0").start();
            }
            var builder = new ProcessBuilder("node", "-e", command.getLast()).redirectError(ProcessBuilder.Redirect.DISCARD);
            var runner = command.stream().filter(argument -> argument.startsWith("ASTROLABE_RUNNER=")).findFirst().orElseThrow();
            builder.environment().put("ASTROLABE_RUNNER", runner.substring("ASTROLABE_RUNNER=".length()));
            return builder.start();
        }
    }
}
