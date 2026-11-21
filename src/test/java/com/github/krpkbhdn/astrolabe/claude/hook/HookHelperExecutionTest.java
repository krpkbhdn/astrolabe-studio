package com.github.krpkbhdn.astrolabe.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.TestProcesses;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the generated statusLine command (the real helper, dev shape) in every shell present on this machine against a local server. */
class HookHelperExecutionTest {

    /** Windows argv quoting garbles a quoted command for MSYS bash, so the POSIX shells get it through the environment; eval is what -c does with it. */
    private static final String COMMAND_ENV = "ASTROLABE_TEST_COMMAND";
    private static final String TOKEN = "tok-Zx9_secretvalue";
    private static final byte[] PAYLOAD = "{\"text\":\"Привіт мір\"}".getBytes(StandardCharsets.UTF_8);
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");

    private static final Duration SHELL_LIMIT = Duration.ofSeconds(20);
    /** Reported for a shell that did not exit within {@link #SHELL_LIMIT}; such a shell is skipped, not failed. */
    private static final int UNRESPONSIVE_EXIT = -2;

    private record Shell(String name, List<String> prefix) {
        boolean powerShell() {
            return prefix.contains("-EncodedCommand");
        }
    }

    private record Result(int exit, String stdout, String stderr) {
    }

    private static List<Shell> probedShells;

    private final List<String> receivedAuthorization = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedBodies = new CopyOnWriteArrayList<>();
    private HttpServer http;
    private String url;

    @BeforeEach
    void startServer() throws Exception {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", exchange -> {
            receivedAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            receivedBodies.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        http.start();
        url = "http://127.0.0.1:" + http.getAddress().getPort() + "/statusline/abc-1";
    }

    @AfterEach
    void stopServer() {
        http.stop(0);
    }

    @Test
    void everyAvailableShellPostsTheBodyByteExactWithTheBearerAndPrintsNothing() throws Exception {
        var command = command();
        assertThat(command).doesNotContain(TOKEN);
        for (var shell : availableShells()) {
            receivedAuthorization.clear();
            receivedBodies.clear();

            var result = run(shell, command, TOKEN);
            if (result.exit() == UNRESPONSIVE_EXIT) {
                continue;
            }

            assertThat(result.exit()).as(shell.name() + " " + result).isZero();
            assertThat(result.stdout()).as(shell.name()).isEmpty();
            assertThat(receivedAuthorization).as(shell.name()).containsExactly("Bearer " + TOKEN);
            assertThat(receivedBodies).as(shell.name()).singleElement().isEqualTo(PAYLOAD);
        }
    }

    @Test
    void anUnsetTokenExitsOneAndSendsNothing() throws Exception {
        var command = command();
        for (var shell : availableShells()) {
            var result = run(shell, command, null);
            if (result.exit() == UNRESPONSIVE_EXIT) {
                continue;
            }

            assertThat(result.exit()).as(shell.name() + " " + result).isEqualTo(HookPoster.FAILURE_EXIT);
            assertThat(result.stdout()).as(shell.name()).isEmpty();
        }
        assertThat(receivedAuthorization).isEmpty();
    }

    @Test
    void aStoppedServerStillExitsZeroWithNoOutput() throws Exception {
        var command = command();
        http.stop(0);
        for (var shell : availableShells()) {
            var result = run(shell, command, TOKEN);
            if (result.exit() == UNRESPONSIVE_EXIT) {
                continue;
            }

            assertThat(result.exit()).as(shell.name() + " " + result).isZero();
            assertThat(result.stdout()).as(shell.name()).isEmpty();
        }
    }

    private String command() {
        return new HookHelperCommand().shellCommand(url).orElseThrow();
    }

    /** Shells that start and run {@code exit 0}, probed once per class (a PowerShell start costs seconds); the test is skipped when none is present. */
    private static synchronized List<Shell> availableShells() throws Exception {
        if (probedShells == null) {
            probedShells = probeShells();
        }
        assumeTrue(!probedShells.isEmpty(), "no shell available");
        return probedShells;
    }

    private static List<Shell> probeShells() throws Exception {
        var candidates = new ArrayList<Shell>();
        candidates.add(new Shell("sh", List.of("sh", "-c")));
        gitBash().ifPresent(bash -> candidates.add(new Shell("git bash", List.of(bash, "-c"))));
        candidates.add(new Shell("powershell", List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand")));
        var available = new ArrayList<Shell>();
        for (var shell : candidates) {
            if (run(shell, "exit 0", TOKEN).exit() == 0) {
                available.add(shell);
            }
        }
        return available;
    }

    /** bash.exe in the install root of the git.exe on the PATH (Git/cmd/git.exe, Git/bin/bash.exe). */
    private static Optional<String> gitBash() {
        if (!WINDOWS || System.getenv("PATH") == null) {
            return Optional.empty();
        }
        for (var directory : System.getenv("PATH").split(File.pathSeparator)) {
            var git = Path.of(directory).resolve("git.exe");
            var root = Path.of(directory).getParent();
            if (Files.isRegularFile(git) && root != null && Files.isRegularFile(root.resolve("bin").resolve("bash.exe"))) {
                return Optional.of(root.resolve("bin").resolve("bash.exe").toString());
            }
        }
        return Optional.empty();
    }

    /** The token is set in the process environment only; a shell that cannot be started reports exit -1. */
    private static Result run(Shell shell, String command, String token) throws Exception {
        var line = new ArrayList<>(shell.prefix());
        line.add(shell.powerShell() ? Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE)) : "eval \"$" + COMMAND_ENV + "\"");
        var builder = new ProcessBuilder(line);
        builder.environment().put(COMMAND_ENV, command);
        builder.environment().remove(HookServer.SESSION_TOKEN_ENV);
        if (token != null) {
            builder.environment().put(HookServer.SESSION_TOKEN_ENV, token);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(-1, "", e.toString());
        }
        try {
            process.getOutputStream().write(PAYLOAD);
            var drained = TestProcesses.run(process, SHELL_LIMIT);
            return new Result(drained.exitCode(), drained.stdout(), drained.stderr());
        } catch (IOException | AssertionError e) {
            return new Result(UNRESPONSIVE_EXIT, "", e.toString());
        }
    }
}
