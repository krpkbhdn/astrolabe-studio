package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.codemode.HostBridge;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

class ContainerSandboxTest {

    private static final SessionContext SESSION = new SessionContext(1, "s");
    private static final String INSTANCE = "0123abcd";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<List<String>> started = Collections.synchronizedList(new ArrayList<>());

    /** A container stand-in: the script lambda gets what the host writes and a writer for what the container prints. */
    private static final class FakeProcess extends Process {
        private final PipedOutputStream hostWrites = new PipedOutputStream();
        private final PipedInputStream containerReads;
        private final PipedOutputStream containerWrites = new PipedOutputStream();
        private final PipedInputStream hostReads;
        private volatile boolean alive = true;

        FakeProcess(BiConsumer<BufferedReader, PrintWriter> script) throws IOException {
            containerReads = new PipedInputStream(hostWrites);
            hostReads = new PipedInputStream(containerWrites);
            Thread.startVirtualThread(() -> {
                var in = new BufferedReader(new InputStreamReader(containerReads, StandardCharsets.UTF_8));
                var out = new PrintWriter(containerWrites, true, StandardCharsets.UTF_8);
                script.accept(in, out);
                if (alive) {
                    out.close();
                }
            });
        }

        @Override public OutputStream getOutputStream() { return hostWrites; }
        @Override public InputStream getInputStream() { return hostReads; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() {
            alive = false;
            try {
                containerWrites.close();
            } catch (IOException e) {
                // test double
            }
        }
    }

    private ContainerSandbox sandbox(Duration timeout, int maxChars, BiConsumer<BufferedReader, PrintWriter> script) {
        return sandbox(timeout, maxChars, "256m", 1, script);
    }

    private ContainerSandbox sandbox(Duration timeout, int maxChars, String memory, double cpus, BiConsumer<BufferedReader, PrintWriter> script) {
        var properties = TestProperties.of(timeout, 100000, maxChars, 10, Duration.ofMinutes(10), "node:22-alpine", memory, cpus, Duration.ofDays(30), Duration.ofSeconds(25));
        return new ContainerSandbox(IsolationBackend.DOCKER, properties, mapper, INSTANCE, command -> {
            started.add(command);
            return command.contains("kill") ? new FakeProcess((in, out) -> {}) : new FakeProcess(script);
        });
    }

    private static final class RecordingBridge implements HostBridge {
        final List<String> logs = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        @Override public String callTool(String server, String tool, String argsJson) {
            calls.add(server + "." + tool + argsJson);
            if (tool.equals("boom")) {
                throw new IllegalStateException("denied");
            }
            return "{\"n\":1}";
        }

        @Override public void log(String line) { logs.add(line); }
    }

    private static void awaitRun(BufferedReader in) {
        try {
            in.readLine();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void roundTripsToolCallsAndLogs() {
        var bridge = new RecordingBridge();
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"log\",\"line\":\"hello\"}");
            out.println("{\"type\":\"call\",\"id\":1,\"server\":\"a\",\"tool\":\"b\",\"args\":{\"x\":1}}");
            try {
                assertThat(in.readLine()).contains("\"type\":\"result\"", "\"id\":1", "{\\\"n\\\":1}");
                out.println("{\"type\":\"call\",\"id\":2,\"server\":\"a\",\"tool\":\"boom\",\"args\":{}}");
                assertThat(in.readLine()).contains("\"error\":\"denied\"");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            out.println("{\"type\":\"done\",\"value\":{\"ok\":true}}");
        }).run("code", bridge, SESSION);

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("hello\n{\"ok\":true}");
        assertThat(bridge.calls).containsExactly("a.b{\"x\":1}", "a.boom{}");
        assertThat(bridge.logs).containsExactly("hello");
        assertThat(started.getFirst()).contains("--network", "none", "--read-only", "--pull", "never").noneMatch(a -> a.equals("-v"));
    }

    @Test
    void reportsScriptError() {
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"error\",\"message\":\"bad\"}");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValue("bad");
    }

    @Test
    void killsTheContainerOnTimeout() {
        var result = sandbox(Duration.ofMillis(200), 1000, (in, out) -> {
            awaitRun(in);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
        assertThat(started).anyMatch(c -> c.contains("kill") && c.getLast().startsWith("astrolabe-code-"));
    }

    @Test
    void truncatesLongOutputKeepingHeadAndTail() {
        var result = sandbox(Duration.ofSeconds(5), 20, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"done\",\"value\":\"" + "a".repeat(50) + "b".repeat(50) + "\"}");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.truncated()).isTrue();
        assertThat(result.output()).startsWith("a".repeat(10)).endsWith("b".repeat(10)).contains("[truncated 80 chars]");
    }

    @Test
    void failsOnMalformedLine() {
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("not json");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValue("The sandbox sent a malformed message");
    }

    @Test
    void failsWhenContainerExitsWithoutResult() {
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> awaitRun(in)).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValue("The container exited without a result");
    }

    @Test
    void keepsPrintedLinesWhenTheScriptFails() {
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"log\",\"line\":\"before\"}");
            out.println("{\"type\":\"error\",\"message\":\"bad\"}");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValue("bad");
        assertThat(result.output()).isEqualTo("before");
    }

    @Test
    void failsAndKillsTheContainerOnAnOverlongLine() {
        var result = sandbox(Duration.ofSeconds(20), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"log\",\"line\":\"before\"}");
            out.print("x".repeat(4 * 1024 * 1024 + 10));
            out.flush();
            sleepQuietly(5000);
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.error()).hasValue("The sandbox sent a line over the size limit");
        assertThat(result.output()).isEqualTo("before");
        assertThat(started).anyMatch(c -> c.contains("kill"));
    }

    @Test
    void bufferedLogTextStaysWithinTheOutputLimit() {
        var result = sandbox(Duration.ofSeconds(20), 100, (in, out) -> {
            awaitRun(in);
            for (int i = 0; i < 2000; i++) {
                out.println("{\"type\":\"log\",\"line\":\"line-" + i + "-" + "z".repeat(50) + "\"}");
            }
            out.println("{\"type\":\"error\",\"message\":\"bad\"}");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.output()).hasSizeLessThan(200).startsWith("line-0-").contains("[truncated").endsWith("z".repeat(50));
    }

    @Test
    void timeoutHoldsWhileAToolCallIsBlocked() throws InterruptedException {
        var bridge = new InterruptibleBlockingBridge();
        var result = sandbox(Duration.ofMillis(300), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"log\",\"line\":\"before\"}");
            out.println("{\"type\":\"call\",\"id\":1,\"server\":\"a\",\"tool\":\"b\",\"args\":{}}");
            sleepQuietly(5000);
        }).run("code", bridge, SESSION);

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("max-script-time"));
        assertThat(result.output()).isEqualTo("before");
        assertThat(started).anyMatch(c -> c.contains("kill"));
        assertThat(bridge.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void commandHasNoSwapAndANoexecTmpfs() {
        sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"done\",\"value\":null}");
        }).run("code", new RecordingBridge(), SESSION);

        var command = started.getFirst();
        assertThat(command.get(command.indexOf("--memory-swap") + 1)).isEqualTo(command.get(command.indexOf("--memory") + 1)).isEqualTo("256m");
        assertThat(command.get(command.indexOf("--tmpfs") + 1)).isEqualTo("/tmp:size=16m,noexec,nosuid");
    }

    @Test
    void rejectsAnImageThatCouldBeReadAsAnOption() {
        for (var image : List.of("--privileged", "-v", "a b", "a\nb", "a\u0000b")) {
            var properties = TestProperties.of(Duration.ofSeconds(1), 1, 100, 10, Duration.ofMinutes(1), image, "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
            var rejecting = new ContainerSandbox(IsolationBackend.DOCKER, properties, mapper, INSTANCE, command -> {
                throw new IllegalStateException("must not start");
            });

            assertThat(rejecting.run("code", new RecordingBridge(), SESSION).error()).hasValue("The container image name is invalid");
        }
    }

    @Test
    void failsWhenProcessCannotStart() {
        var properties = TestProperties.of(Duration.ofSeconds(1), 1, 100, 10, Duration.ofMinutes(1), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var failing = new ContainerSandbox(IsolationBackend.PODMAN, properties, mapper, INSTANCE, command -> {
            throw new IOException("no such file");
        });

        assertThat(failing.run("code", new RecordingBridge(), SESSION).error()).hasValueSatisfying(e -> assertThat(e).contains("podman is unavailable"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASTROLABE_REAL_DOCKER", matches = "1")
    void runsAScriptInARealContainer() {
        var properties = TestProperties.of(Duration.ofSeconds(60), 1, 1000, 10, Duration.ofMinutes(1), "node:22-alpine", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        var bridge = new RecordingBridge();
        var result = new ContainerSandbox(IsolationBackend.DOCKER, properties, mapper, INSTANCE)
                .run("console.log('hi'); const r = await servers.a.b({x: 1}); return r.n + 1;", bridge, SESSION);

        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("hi\n2");
    }

    @Test
    void containerIsLabelledAndRunsUnderAHardTimeout() {
        sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"done\",\"value\":\"\"}");
        }).run("1", new RecordingBridge(), SESSION);

        var command = started.get(0);
        assertThat(command).containsSubsequence("--label", "astrolabe-code=" + INSTANCE);
        assertThat(command).containsSubsequence("node:22-alpine", "timeout", "-s", "KILL", "3610", "node", "-e");
    }

    @Test
    void anInvalidInstanceIdIsRefusedBeforeItCouldReachTheCommandLine() {
        var properties = TestProperties.of(Duration.ofSeconds(1), 1, 100, 10, Duration.ofMinutes(1), "img", "256m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
        for (var invalid : java.util.Arrays.asList("1", "UPPERCASE1", "abc def0", "--privileged", "", null)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ContainerSandbox(IsolationBackend.DOCKER, properties, mapper, invalid, command -> {
                throw new IllegalStateException("must not start");
            })).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void lineBreaksAndAnEmptyFirstLineAreKeptWithoutATrailingNewline() {
        var result = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"log\",\"line\":\"\"}");
            out.println("{\"type\":\"log\",\"line\":\"x\"}");
            out.println("{\"type\":\"done\",\"value\":\"r\"}");
        }).run("code", new RecordingBridge(), SESSION);

        assertThat(result.output()).isEqualTo("\nx\nr");
    }

    @Test
    void invalidMemoryOrCpuLimitsNeverReachTheCommandLine() {
        for (var memory : List.of("--privileged", "0", "256 m", "-1m", "")) {
            var result = sandbox(Duration.ofSeconds(5), 1000, memory, 1, (in, out) -> {}).run("1", new RecordingBridge(), SESSION);
            assertThat(result.error()).hasValue(ContainerSandbox.INVALID_LIMITS);
        }
        for (var cpus : new double[] {0, -1, 65, Double.NaN, Double.POSITIVE_INFINITY}) {
            var result = sandbox(Duration.ofSeconds(5), 1000, "256m", cpus, (in, out) -> {}).run("1", new RecordingBridge(), SESSION);
            assertThat(result.error()).hasValue(ContainerSandbox.INVALID_LIMITS);
        }
        assertThat(started).isEmpty();
    }

    @Test
    void emptyDoneValueAddsNothingAndStringsStayRaw() {
        var empty = sandbox(Duration.ofSeconds(5), 1000, (in, out) -> {
            awaitRun(in);
            out.println("{\"type\":\"done\",\"value\":\"\"}");
        }).run("1", new RecordingBridge(), SESSION);

        assertThat(empty.output()).isEmpty();
    }
}
