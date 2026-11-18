package com.github.krpkbhdn.astrolabe.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HookPosterTest {

    private static final String TOKEN = "tok-Zx9_secretvalue";
    private static final byte[] BODY = "{\"text\":\"Привіт мір\"}".getBytes(StandardCharsets.UTF_8);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> evilAuthorization = new CopyOnWriteArrayList<>();
    private HttpServer http;
    private HttpServer evil;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        evil = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        evil.createContext("/", exchange -> {
            evilAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        evil.start();
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(exchange.getRequestBody().readAllBytes());
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "http://localhost:" + evil.getAddress().getPort() + "/stolen");
                exchange.sendResponseHeaders(302, -1);
            } else if (exchange.getRequestURI().getPath().equals("/401")) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        http.stop(0);
        evil.stop(0);
    }

    private int run(String token, String... args) {
        return HookPoster.run(args, token, new ByteArrayInputStream(BODY), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void postsTheBodyByteExactWithTheBearerAndExitsZero() {
        assertThat(run(TOKEN, HookPoster.FLAG, base + "/statusline/abc-1")).isZero();

        assertThat(requests).containsExactly("POST /statusline/abc-1");
        assertThat(authorization).containsExactly("Bearer " + TOKEN);
        assertThat(bodies).singleElement().isEqualTo(BODY);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void anUnsetOrEmptyTokenExitsOneWithOneStderrLineAndSendsNothing() {
        assertThat(run(null, HookPoster.FLAG, base + "/x")).isEqualTo(1);
        assertThat(run("", HookPoster.FLAG, base + "/x")).isEqualTo(1);

        assertThat(requests).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(2).allMatch(line -> line.contains("ASTROLABE_HOOK_TOKEN"));
    }

    @Test
    void theTokenFileIsUsedAloneTrimmedAndWinsOverTheEnvironment(@TempDir Path dir) throws Exception {
        var file = dir.resolve("token");
        Files.writeString(file, "  file-token\r\n");

        assertThat(run(null, HookPoster.FLAG, base + "/a", HookPoster.TOKEN_FILE_FLAG, file.toString())).isZero();
        assertThat(run("env-token", HookPoster.FLAG, base + "/b", HookPoster.TOKEN_FILE_FLAG, file.toString())).isZero();

        assertThat(authorization).containsExactly("Bearer file-token", "Bearer file-token");
    }

    @Test
    void theEnvironmentTokenAloneIsStillUsed() {
        assertThat(run("env-token", HookPoster.FLAG, base + "/a")).isZero();

        assertThat(authorization).containsExactly("Bearer env-token");
    }

    @Test
    void anUnreadableTokenFileFallsBackToTheEnvironmentAndNoTokenExitsOne(@TempDir Path dir) {
        var missing = dir.resolve("missing").toString();

        assertThat(run("env-token", HookPoster.FLAG, base + "/a", HookPoster.TOKEN_FILE_FLAG, missing)).isZero();
        assertThat(authorization).containsExactly("Bearer env-token");

        assertThat(run(null, HookPoster.FLAG, base + "/a", HookPoster.TOKEN_FILE_FLAG, missing)).isEqualTo(1);
        assertThat(requests).hasSize(1);
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(1);
    }

    @Test
    void aMissingOrNonLoopbackHttpUrlExitsOneWithoutAnyRequest() {
        assertThat(run(TOKEN, HookPoster.FLAG)).isEqualTo(1);
        assertThat(run(TOKEN, HookPoster.FLAG, "https://127.0.0.1/x")).isEqualTo(1);
        assertThat(run(TOKEN, HookPoster.FLAG, "http://example.com/x")).isEqualTo(1);
        assertThat(run(TOKEN, HookPoster.FLAG, "file:///etc/passwd")).isEqualTo(1);
        assertThat(run(TOKEN, HookPoster.FLAG, "not a url")).isEqualTo(1);

        assertThat(requests).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(5);
    }

    @Test
    void aLargePayloadAndRawBytesSurviveUntouched() {
        var large = ("{\"x\":\"" + "ж".repeat(100_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        var raw = new byte[] {'{', 0, '\n', '\r', '\n', (byte) 0xff, (byte) 0xfe, 0, '}'};

        for (var body : List.of(large, raw, new byte[0])) {
            assertThat(HookPoster.run(new String[] {HookPoster.FLAG, base + "/a"}, TOKEN, new ByteArrayInputStream(body), System.err)).isZero();
            assertThat(bodies.getLast()).isEqualTo(body);
        }
    }

    @Test
    void theQueryIsKeptAndATokenWithSpecialCharactersIsSentVerbatim() {
        var token = "a+b/c=d%e&j;k|l$h";

        assertThat(run(token, HookPoster.FLAG, base + "/statusline/s-1?x=1&y=%D0%96")).isZero();

        assertThat(requests).containsExactly("POST /statusline/s-1?x=1&y=%D0%96");
        assertThat(authorization).containsExactly("Bearer " + token);
    }

    @Test
    void concurrentCallsAllArriveIntact() throws Exception {
        var threads = new ArrayList<Thread>();
        for (var i = 0; i < 8; i++) {
            var thread = Thread.ofPlatform().start(() -> run(TOKEN, HookPoster.FLAG, base + "/a"));
            threads.add(thread);
        }
        for (var thread : threads) {
            thread.join();
        }

        assertThat(bodies).hasSize(8).allSatisfy(body -> assertThat(body).isEqualTo(BODY));
    }

    @Test
    void aStoppedServerStillExitsZeroWithOneStderrLineAndNoToken() {
        http.stop(0);

        assertThat(run(TOKEN, HookPoster.FLAG, base + "/x")).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(1).allSatisfy(line -> assertThat(line).doesNotContain(TOKEN));
    }

    private int runUrl(String url) {
        return run(TOKEN, HookPoster.FLAG, url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1@evil.com/x", "http://localhost.evil.com/x", "http://127.0.0.1.evil.com/x", "https://localhost/x",
            "http://evil.com#@127.0.0.1/x", "http://0.0.0.0/x", "http://[::2]/x", "", "http:///x",
            "ftp://127.0.0.1/x", "HTTP2://127.0.0.1/x"})
    void lookalikeOrForeignUrlsExitOneWithOneLineAndNothingSent(String url) {
        assertThat(runUrl(url)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain(TOKEN);
    }

    @Test
    void userinfoOnALoopbackHostNeverReachesAnotherHost() {
        var url = "http://127.0.0.1@localhost:" + evil.getAddress().getPort() + "/x";
        runUrl(url);
        assertThat(evilAuthorization).as("only whatever host the URI really names may receive it").hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void aRedirectToAnotherHostIsNotFollowedAndTheBearerNeverReachesIt() {
        assertThat(runUrl(base + "/redirect")).isZero();
        assertThat(requests).containsExactly("POST /redirect");
        assertThat(evilAuthorization).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(1).allSatisfy(l -> assertThat(l).contains("302").doesNotContain(TOKEN));
    }

    @Test
    void a401AnswerExitsZeroWithOneStderrLineAndEmptyToken() {
        assertThat(runUrl(base + "/401")).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).singleElement().asString().contains("401").doesNotContain(TOKEN);
    }

    @Test
    @Timeout(8)
    void aHungServerStaysWithinTheHookBudget() throws Exception {
        try (var hung = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var started = System.nanoTime();
            var exit = runUrl("http://127.0.0.1:" + hung.getLocalPort() + "/x");
            var took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(exit).isZero();
            assertThat(took).isLessThan(Duration.ofSeconds(5));
            assertThat(err.toString(StandardCharsets.UTF_8).lines()).hasSize(1);
        }
    }

    @Test
    void garbledArgsDoNotThrow() {
        assertThatCode(() -> HookPoster.run(new String[] {HookPoster.FLAG, "http://[::1"}, TOKEN, new ByteArrayInputStream(new byte[0]), System.err))
                .doesNotThrowAnyException();
        assertThat(HookPoster.run(new String[] {HookPoster.FLAG, "http://[::1"}, TOKEN, new ByteArrayInputStream(new byte[0]), new PrintStream(err))).isEqualTo(1);
        assertThat(HookPoster.run(new String[] {HookPoster.FLAG, "%%%"}, TOKEN, new ByteArrayInputStream(new byte[0]), new PrintStream(err))).isEqualTo(1);
    }
}
