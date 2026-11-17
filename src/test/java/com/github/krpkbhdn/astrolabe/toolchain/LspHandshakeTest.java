package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.toolchain.LspHandshake.Outcome;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LspHandshakeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final LspHandshake handshake = new LspHandshake();
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private static String frame(String json) {
        return "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
    }

    private LspHandshake.Result run(String serverOutput) {
        return handshake.perform(new ByteArrayInputStream(serverOutput.getBytes(StandardCharsets.UTF_8)), sent, TIMEOUT);
    }

    @Test
    void respondsWhenServerAnswersInitializeWithCapabilities() {
        var result = run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"));

        assertThat(result.outcome()).isEqualTo(Outcome.RESPONDED);
        assertThat(sent.toString(StandardCharsets.UTF_8))
                .contains("\"method\":\"initialized\"", "\"method\":\"shutdown\"", "\"method\":\"exit\"");
    }

    @Test
    void failsOnErrorResponse() {
        var result = run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"boom\"}}"));

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.detail()).isEqualTo("server answered initialize with an error: boom");
    }

    @Test
    void errorMessageIsCollapsedTruncatedAndPathsReplaced() {
        var message = "Could not find  TypeScript in C:\\\\Users\\\\me\\\\npm\\\\node_modules and /home/me/.npm/lib. " + "x".repeat(300);
        var result = run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"" + message + "\"}}"));

        assertThat(result.detail()).startsWith("server answered initialize with an error: Could not find TypeScript in <path> and <path> xxx")
                .doesNotContain("Users", "/home")
                .hasSize("server answered initialize with an error: ".length() + 140);
    }

    @Test
    void errorWithoutMessageKeepsPlainText() {
        var result = run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603}}"));

        assertThat(result.detail()).isEqualTo("server answered initialize with an error");
    }

    @Test
    void failsWhenResultHasNoCapabilities() {
        assertThat(run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void skipsLogLinesAndOtherMessagesBeforeTheResponse() {
        var output = "starting server\nloading index...\r\n"
                + frame("{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{}}")
                + frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}");

        assertThat(run(output).outcome()).isEqualTo(Outcome.RESPONDED);
    }

    @Test
    void serverRequestWithIdOneIsNotTheInitializeResponse() {
        var output = frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"workspace/configuration\",\"params\":{}}")
                + frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}");

        assertThat(run(output).outcome()).isEqualTo(Outcome.RESPONDED);
        assertThat(run(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"client/registerCapability\"}")).outcome())
                .isEqualTo(Outcome.FAILED);
    }

    @Test
    void reportsNoResponseInTimeWhenServerStaysSilent() throws IOException {
        try (var silent = new PipedInputStream(new PipedOutputStream())) {
            var result = handshake.perform(silent, sent, Duration.ofMillis(200));

            assertThat(result.outcome()).isEqualTo(Outcome.NO_RESPONSE_IN_TIME);
        }
    }

    @Test
    void failsWhenServerClosesItsOutputEarly() {
        assertThat(run("").outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void failsOnMalformedJson() {
        assertThat(run(frame("not json")).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void writesInitializeWithMatchingContentLength() {
        run("");

        var written = sent.toString(StandardCharsets.UTF_8);
        int headerEnd = written.indexOf("\r\n\r\n");
        int length = Integer.parseInt(written.substring("Content-Length: ".length(), headerEnd));
        var body = written.substring(headerEnd + 4);
        assertThat(written).startsWith("Content-Length: ");
        assertThat(body).contains("\"method\":\"initialize\"");
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(length);
    }

    @Test
    void sendsNoRootUriByDefaultAndTheWorkspaceFolderWhenGiven() {
        run("");
        assertThat(sent.toString(StandardCharsets.UTF_8)).contains("\"rootUri\":null");

        sent.reset();
        var workspace = java.nio.file.Path.of("probe-workspace").toAbsolutePath();
        handshake.perform(new ByteArrayInputStream(new byte[0]), sent, TIMEOUT, workspace);

        assertThat(sent.toString(StandardCharsets.UTF_8)).contains("\"rootUri\":\"" + workspace.toUri() + "\"");
    }

    private static Process failingServer(String stderr) {
        return new Process() {
            @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)); }
            @Override public int waitFor() { return 1; }
            @Override public int exitValue() { return 1; }
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { return this; }
            @Override public long pid() { return Long.MAX_VALUE; }
        };
    }

    @Test
    void failureDetailCarriesTheTailOfStderrWithPathsReplaced() {
        var stderr = "starting\nCannot lock C:\\Users\\me\\state\\lock\n" + "x".repeat(500) + "\nlast line";

        var result = handshake.performAndStop(failingServer(stderr), TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.detail()).startsWith("unreadable response").contains("; stderr: ...").endsWith("xxxx last line")
                .doesNotContain("Users");
        assertThat(result.detail().substring(result.detail().indexOf("; stderr: "))).hasSize("; stderr: ...".length() + LspHandshake.MAX_STDERR_DETAIL);
    }

    @Test
    void failureWithoutStderrKeepsTheOriginalDetail() {
        var result = handshake.performAndStop(failingServer(""), TIMEOUT);

        assertThat(result.detail()).doesNotContain("stderr");
    }

    @Test
    void failsWhenTheCommandCannotBeStarted() {
        var result = handshake.perform(java.util.List.of("definitely-not-a-real-binary-astrolabe"), TIMEOUT);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void stopsTheProcessAndClosesItsStreams() {
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var killed = new java.util.concurrent.atomic.AtomicBoolean();
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.ByteArrayOutputStream() { @Override public void close() { closed.incrementAndGet(); } };
            }
            @Override public InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(new byte[0]) { @Override public void close() { closed.incrementAndGet(); } };
            }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { killed.set(true); }
            @Override public Process destroyForcibly() { killed.set(true); return this; }
            @Override public long pid() { return Long.MAX_VALUE; }
        };

        var result = handshake.performAndStop(process, TIMEOUT);

        assertThat(result.outcome()).isEqualTo(LspHandshake.Outcome.FAILED);
        assertThat(killed).isTrue();
        assertThat(closed.get()).isGreaterThanOrEqualTo(2);
    }
}
