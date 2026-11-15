package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.toolchain.ArchiveKind;
import com.github.krpkbhdn.ccmanager.toolchain.DownloadSource;
import com.github.krpkbhdn.ccmanager.toolchain.LatestSource;
import com.github.krpkbhdn.ccmanager.toolchain.LauncherSpec;
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition;
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind;
import com.github.krpkbhdn.ccmanager.toolchain.ToolchainProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ManagedToolInstallerTest {

    private static final ToolchainProperties PROPERTIES = new ToolchainProperties(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(10), DataSize.ofGigabytes(1),
            DataSize.ofGigabytes(4), 200_000, Duration.ofSeconds(30), 5, 2000, 500);
    private static final ToolDefinition TOOL = new ToolDefinition("demo", "Demo", "g", ToolKind.LANGUAGE_SERVER, "demo", List.of(),
            "", "", "", List.of(), Map.of(), "", Map.of(), Map.of());

    @TempDir
    Path dataDir;

    private LocalHttp server;
    private ManagedToolsDirectory directory;
    private ManagedToolInstaller installer;
    private final AtomicReference<String> latest = new AtomicReference<>("v1.0.0");
    private final AtomicReference<String> checksumOverride = new AtomicReference<>();
    private final AtomicInteger archiveRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = new LocalHttp();
        directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        installer = new ManagedToolInstaller(directory, new LauncherWriter(directory), PROPERTIES, server.http(),
                LauncherWriter.Platform.LINUX);
        server.route("/latest.txt", exchange -> send(exchange, latest.get().getBytes(StandardCharsets.UTF_8)));
        serveArchive("1.0.0", "one");
        serveArchive("1.1.0", "two");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private void serveArchive(String version, String content) throws IOException {
        byte[] archive = zip(version, content);
        server.route("/pkg/" + version + "/demo.zip", exchange -> {
            archiveRequests.incrementAndGet();
            send(exchange, archive);
        });
        server.route("/pkg/" + version + "/demo.zip.sha256", exchange -> {
            var override = checksumOverride.get();
            send(exchange, (override != null ? override : sha256(archive) + "  demo.zip\n").getBytes(StandardCharsets.UTF_8));
        });
    }

    private DownloadSource zipSource() {
        return new DownloadSource(new LatestSource(LatestSource.Kind.TEXT_FILE, server.uri("/latest.txt").toString(), "v(\\S+)"),
                server.uri("/").toString() + "pkg/{version}/demo.zip", ArchiveKind.ZIP, new LauncherSpec(LauncherSpec.Kind.BINARY, "bin/demo"));
    }

    @Test
    void downloadsVerifiesUnpacksAndWritesTheLauncher() throws Exception {
        var output = new ArrayList<String>();

        var installed = installer.install(TOOL, zipSource(), output::add);

        var version = directory.versionDirectory("demo", "1.0.0");
        assertThat(installed.version()).isEqualTo("1.0.0");
        assertThat(installed.upToDate()).isFalse();
        assertThat(version.resolve("bin/demo")).hasContent("one");
        assertThat(version.resolve(ManagedToolsDirectory.COMPLETE_MARKER)).exists();
        assertThat(installed.launcher()).isEqualTo(directory.binDirectory().resolve("demo")).exists();
        assertThat(Files.readString(installed.launcher())).contains(version.resolve("bin/demo").toString());
        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(output.getLast()).contains("Downloading 100%").contains("Checksum verified").contains("Launcher:");
        assertThat(entries(directory.downloadsDirectory())).isEmpty();
    }

    @Test
    void sameVersionAgainOnlyRewritesTheLauncher() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        Files.delete(directory.binDirectory().resolve("demo"));

        var again = installer.install(TOOL, zipSource(), line -> {});

        assertThat(again.upToDate()).isTrue();
        assertThat(archiveRequests).hasValue(1);
        assertThat(directory.binDirectory().resolve("demo")).exists();
    }

    @Test
    void newVersionReplacesTheOldOneAfterTheSwitch() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        latest.set("v1.1.0");

        var updated = installer.install(TOOL, zipSource(), line -> {});

        assertThat(updated.version()).isEqualTo("1.1.0");
        assertThat(directory.versionDirectory("demo", "1.1.0").resolve("bin/demo")).hasContent("two");
        assertThat(directory.versionDirectory("demo", "1.0.0")).doesNotExist();
        assertThat(directory.installedVersion("demo")).contains("1.1.0");
    }

    @Test
    void checksumMismatchLeavesTheOldVersionAndNoPartialFolder() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        latest.set("v1.1.0");
        checksumOverride.set("0".repeat(64) + "  demo.zip");

        assertThatThrownBy(() -> installer.install(TOOL, zipSource(), line -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("SHA-256 mismatch");

        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(directory.versionDirectory("demo", "1.0.0").resolve("bin/demo")).hasContent("one");
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
        assertThat(entries(directory.downloadsDirectory())).isEmpty();
    }

    @Test
    void missingChecksumFileFailsClosed() throws Exception {
        checksumOverride.set("not a checksum");

        assertThatThrownBy(() -> installer.install(TOOL, zipSource(), line -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("SHA-256");

        assertThat(directory.installedVersion("demo")).isEmpty();
        assertThat(archiveRequests).hasValue(0);
    }

    @Test
    void unsafeVersionStringIsRefusedBeforeAnyDownload() {
        latest.set("v1.0/../evil");

        assertThatThrownBy(() -> installer.install(TOOL, zipSource(), line -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("Unusable version");

        assertThat(archiveRequests).hasValue(0);
        assertThat(directory.root().resolve("demo")).doesNotExist();
    }

    @Test
    void tarGzWithJdtlsLauncherIsUnpackedAndTheExecutableBitKept() throws Exception {
        byte[] archive = tarGz("2.0.0", "plugins/org.eclipse.equinox.launcher_1.jar", "jar");
        server.route("/pkg/2.0.0/demo.tar.gz", exchange -> send(exchange, archive));
        server.body("/pkg/2.0.0/demo.tar.gz.sha256", sha256(archive));
        latest.set("v2.0.0");
        var source = new DownloadSource(new LatestSource(LatestSource.Kind.TEXT_FILE, server.uri("/latest.txt").toString(), "v(\\S+)"),
                server.uri("/").toString() + "pkg/{version}/demo.tar.gz", ArchiveKind.TAR_GZ, new LauncherSpec(LauncherSpec.Kind.JDTLS, ""));

        var installed = installer.install(TOOL, source, line -> {});

        assertThat(directory.versionDirectory("demo", "2.0.0").resolve("plugins/org.eclipse.equinox.launcher_1.jar")).hasContent("jar");
        assertThat(Files.readString(installed.launcher())).contains("org.eclipse.equinox.launcher_1.jar");
    }

    @Test
    void interruptDuringTheDownloadStopsItAndCleansUp() throws Exception {
        var slowStarted = new CountDownLatch(1);
        latest.set("v3.0.0");
        server.body("/pkg/3.0.0/demo.zip.sha256", "0".repeat(64));
        server.route("/pkg/3.0.0/demo.zip", exchange -> {
            exchange.sendResponseHeaders(200, 64 * 1024 * 200L);
            var chunk = new byte[64 * 1024];
            try {
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                    slowStarted.countDown();
                    Thread.sleep(20);
                }
            } catch (IOException | InterruptedException e) {
                // the client went away
            }
            exchange.close();
        });
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                installer.install(TOOL, zipSource(), line -> {});
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();

        worker.interrupt();
        worker.join(10_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(directory.installedVersion("demo")).isEmpty();
        assertThat(entries(directory.downloadsDirectory())).isEmpty();
        assertThat(entries(directory.root().resolve("demo"))).isEmpty();
    }

    @Test
    void sameVersionWithAMissingBinaryIsInstalledAgain() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        Files.delete(directory.versionDirectory("demo", "1.0.0").resolve("bin/demo"));

        var again = installer.install(TOOL, zipSource(), line -> {});

        assertThat(again.upToDate()).isFalse();
        assertThat(archiveRequests).hasValue(2);
        assertThat(directory.versionDirectory("demo", "1.0.0").resolve("bin/demo")).hasContent("one");
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
    }

    @Test
    void launcherWriteFailureKeepsTheOldVersionAndLeavesNoNewFolder() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var launcher = directory.binDirectory().resolve("demo");
        Files.delete(launcher);
        Files.createDirectories(launcher.resolve("blocker"));
        latest.set("v1.1.0");

        assertThatThrownBy(() -> installer.install(TOOL, zipSource(), line -> {}))
                .isInstanceOf(IOException.class).hasMessage("Close sessions that use Demo and retry");

        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(directory.versionDirectory("demo", "1.1.0")).doesNotExist();
    }

    @Test
    void reinstallOfTheSameVersionFetchesAFreshCopyAndLeavesOneFolder() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var binary = directory.versionDirectory("demo", "1.0.0").resolve("bin/demo");
        Files.writeString(binary, "broken");

        var repaired = installer.reinstall(TOOL, zipSource(), line -> {});

        assertThat(repaired.upToDate()).isFalse();
        assertThat(archiveRequests).hasValue(2);
        assertThat(binary).hasContent("one");
        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
    }

    @Test
    void failedReinstallDownloadLeavesTheOldInstallationUntouched() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var binary = directory.versionDirectory("demo", "1.0.0").resolve("bin/demo");
        Files.writeString(binary, "old");
        checksumOverride.set("0".repeat(64) + "  demo.zip");

        assertThatThrownBy(() -> installer.reinstall(TOOL, zipSource(), line -> {})).isInstanceOf(IOException.class);

        assertThat(binary).hasContent("old");
        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(directory.binDirectory().resolve("demo")).exists();
    }

    @Test
    void reinstallThatCannotWriteTheLauncherRestoresTheOldFolder() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var binary = directory.versionDirectory("demo", "1.0.0").resolve("bin/demo");
        Files.writeString(binary, "old");
        var launcher = directory.binDirectory().resolve("demo");
        Files.delete(launcher);
        Files.createDirectories(launcher.resolve("blocker"));

        assertThatThrownBy(() -> installer.reinstall(TOOL, zipSource(), line -> {})).isInstanceOf(IOException.class);

        assertThat(binary).hasContent("old");
        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
    }

    @Test
    void interruptedReinstallKeepsTheOldInstallationAndLeavesNoPartialFolder() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var binary = directory.versionDirectory("demo", "1.0.0").resolve("bin/demo");
        Files.writeString(binary, "old");

        assertThatThrownBy(() -> installer.reinstall(TOOL, zipSource(), text -> {
            if (text.contains("Checksum verified")) {
                Thread.currentThread().interrupt();
            }
        })).isInstanceOf(InterruptedException.class);

        assertThat(Thread.interrupted()).isFalse();
        assertThat(binary).hasContent("old");
        assertThat(directory.installedVersion("demo")).contains("1.0.0");
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
        assertThat(entries(directory.downloadsDirectory())).isEmpty();
    }

    @Test
    void failedReinstallLauncherWriteLeavesNoStaleAsideFolderAndKeepsTheOldMarker() throws Exception {
        installer.install(TOOL, zipSource(), line -> {});
        var launcher = directory.binDirectory().resolve("demo");
        Files.delete(launcher);
        Files.createDirectories(launcher.resolve("blocker"));

        assertThatThrownBy(() -> installer.reinstall(TOOL, zipSource(), line -> {})).isInstanceOf(IOException.class);

        assertThat(directory.versionDirectory("demo", "1.0.0").resolve(ManagedToolsDirectory.COMPLETE_MARKER)).exists();
        assertThat(entries(directory.root().resolve("demo"))).noneMatch(name -> name.contains("partial"));
    }

    @Test
    void interruptDuringTheExtractionIsReportedAsInterrupted() throws Exception {
        assertThatThrownBy(() -> installer.install(TOOL, zipSource(), text -> {
            if (text.contains("Checksum verified")) {
                Thread.currentThread().interrupt();
            }
        })).isInstanceOf(InterruptedException.class);

        assertThat(Thread.interrupted()).isFalse();
        assertThat(directory.installedVersion("demo")).isEmpty();
        assertThat(entries(directory.downloadsDirectory())).isEmpty();
        assertThat(entries(directory.root().resolve("demo"))).isEmpty();
    }

    @Test
    void staleDownloadPartsAndPartialFoldersAreSweptAtTheStart() throws Exception {
        Files.createDirectories(directory.downloadsDirectory());
        Files.writeString(directory.downloadsDirectory().resolve("download-old.part"), "x");
        Files.createDirectories(directory.versionDirectory("demo", "0.9.0.partial-abc"));
        Files.createDirectories(directory.versionDirectory("demo", "0.9.0.partial-stale-def"));

        installer.install(TOOL, zipSource(), line -> {});

        assertThat(entries(directory.downloadsDirectory())).isEmpty();
        assertThat(entries(directory.root().resolve("demo"))).containsExactlyInAnyOrder("1.0.0", "state");
    }

    private static List<String> entries(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] zip(String version, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("demo-" + version + "/bin/demo"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("demo-" + version + "/lib/readme.txt"));
            zip.write("r".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] tarGz(String version, String file, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            var data = content.getBytes(StandardCharsets.UTF_8);
            var entry = new TarArchiveEntry("demo-" + version + "/" + file);
            entry.setSize(data.length);
            entry.setMode(0755);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
