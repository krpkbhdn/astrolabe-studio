package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.toolchain.managed.JdtlsWorkspace;
import com.github.krpkbhdn.astrolabe.toolchain.managed.LauncherWriter;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannelStore;
import com.github.krpkbhdn.astrolabe.workspace.SystemHostEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Real jdtls (~48 MB, pinned milestone) opening a Gradle fixture: the bundle must redirect output to build/eclipse-out; ASTROLABE_REAL_TOOLCHAIN=1. */
@EnabledIfEnvironmentVariable(named = "ASTROLABE_REAL_TOOLCHAIN", matches = "1")
class RealJdtlsOutputRedirectTest {

    private static final Path FIXTURE = Path.of("src/test/resources/toolchain/gradle-fixture");
    private static final List<String> MODULES = List.of("", "sub", "lib");
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);
    private static final String GUARD_MESSAGE = "jdtls output bundle or its configuration is missing";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path dataDir;

    @TempDir
    Path workDir;

    private ManagedToolsDirectory directory;
    private Path project;

    @BeforeEach
    void installJdtls() throws IOException {
        var properties = new ToolchainProperties(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(10), DataSize.ofGigabytes(1),
                DataSize.ofGigabytes(4), 200000, Duration.ofSeconds(30), 5, 2000, 500);
        directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        var host = new SystemHostEnvironment();
        var catalog = new ToolCatalog();
        var lookup = new ExecutableLookup(host, directory);
        var probe = new ToolProbe(catalog, lookup, host, new LspHandshake(), properties, directory);
        var service = new ToolInstallService(catalog, probe, lookup, host, properties, directory, new LauncherWriter(directory),
                new ToolChannelStore(directory));

        var result = service.install("jdtls", text -> { });

        assertThat(result.outcome()).as(result.message()).isEqualTo(ToolInstallService.Outcome.INSTALLED);
        project = Files.createDirectory(workDir.toRealPath().resolve("project"));
        copyTree(FIXTURE, project);
    }

    @Test
    void redirectsEveryGradleModuleToEclipseOut() throws Exception {
        var process = startLauncher();
        try {
            var output = new LspOutput(process.getInputStream());
            var in = process.getOutputStream();
            send(in, 1, "initialize", "{\"processId\":null,\"rootUri\":\"" + project.toUri() + "\",\"capabilities\":{}}");
            output.awaitContains("capabilities", READY_TIMEOUT);
            notify(in, "initialized", "{}");
            notify(in, "textDocument/didOpen", "{\"textDocument\":{\"uri\":\"" + project.resolve("src/main/java/A.java").toUri()
                    + "\",\"languageId\":\"java\",\"version\":1,\"text\":" + quoted(Files.readString(project.resolve("src/main/java/A.java"))) + "}}");
            awaitMarker();
            send(in, 2, "shutdown", "null");
            notify(in, "exit", "null");
        } finally {
            destroyTree(process);
        }

        assertNoEclipseMetadataAndOutputsExist();
        assertMarkerHasOkForEveryModule();
    }

    @Test
    void launcherWithoutBundleFailsFastWithoutCreatingBin() throws Exception {
        var version = directory.installedVersion("jdtls").orElseThrow();
        Files.delete(directory.versionDirectory("jdtls", version).resolve(".astrolabe").resolve("dev.astrolabe.jdtls.output.jar"));

        var process = startLauncher();
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(stderr).contains(GUARD_MESSAGE);
        assertThat(project.resolve("bin")).doesNotExist();
    }

    private Process startLauncher() throws IOException {
        var launcher = directory.launcherFile("jdtls", WINDOWS);
        var command = WINDOWS ? List.of("cmd.exe", "/c", launcher.toString()) : List.of(launcher.toString());
        return new ProcessBuilder(command).directory(project.toFile()).start();
    }

    private void awaitMarker() throws Exception {
        var marker = JdtlsWorkspace.markerFile(directory.stateDirectory("jdtls"), project);
        var deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker) && Files.readAllLines(marker).stream().filter(line -> line.contains(" OK")).count() >= MODULES.size()) {
                return;
            }
            Thread.sleep(1000);
        }
    }

    private void assertNoEclipseMetadataAndOutputsExist() {
        for (var module : MODULES) {
            var root = project.resolve(module);
            assertThat(root.resolve("bin")).as(module + " bin").doesNotExist();
            for (var name : List.of(".project", ".classpath", ".settings")) {
                assertThat(root.resolve(name)).as(module + " " + name).doesNotExist();
            }
            assertThat(root.resolve("build/eclipse-out/main")).as(module + " main").isDirectory();
            assertThat(root.resolve("build/eclipse-out/test")).as(module + " test").isDirectory();
        }
    }

    private void assertMarkerHasOkForEveryModule() throws IOException {
        var marker = JdtlsWorkspace.markerFile(directory.stateDirectory("jdtls"), project);
        var lines = Files.readAllLines(marker);
        for (var module : MODULES) {
            var expected = project.resolve(module).normalize().toString();
            assertThat(lines).as(lines.toString()).anyMatch(line -> line.equals(expected + " OK build/eclipse-out"));
        }
    }

    private static void send(OutputStream out, int id, String method, String params) throws IOException {
        write(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
    }

    private static void notify(OutputStream out, String method, String params) throws IOException {
        write(out, "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + "}");
    }

    private static void write(OutputStream out, String json) throws IOException {
        var body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static void destroyTree(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (var source : paths.toList()) {
                var target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    /** Drains the server's stdout on a thread, so the pipe never blocks it, and lets the test wait for a piece of text. */
    private static final class LspOutput {

        private final StringBuffer text = new StringBuffer();

        LspOutput(InputStream stream) {
            var reader = new Thread(() -> {
                var buffer = new byte[8192];
                try {
                    for (int read; (read = stream.read(buffer)) > 0; ) {
                        text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // the process was destroyed
                }
            }, "jdtls-stdout");
            reader.setDaemon(true);
            reader.start();
        }

        void awaitContains(String piece, Duration timeout) throws InterruptedException {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (!text.toString().contains(piece) && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(text.toString()).contains(piece);
        }
    }
}
