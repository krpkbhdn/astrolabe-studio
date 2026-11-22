package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.download.VerifiedDownload;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class UpdateServiceTest {

    private static final byte[] INSTALLER = "installer-bytes".getBytes(StandardCharsets.UTF_8);
    private static final Installation INSTALLATION = new Installation(InstallKind.DEB, "x64", Path.of("root"), Path.of("root", "bin", "app"));

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String checksum;
    private volatile String latestJson = "{\"tag_name\":\"v1.0.1\",\"html_url\":\"https://github.com/o/r/releases/tag/v1.0.1\",\"assets\":[]}";
    private final PlatformUpdater updater = mock(PlatformUpdater.class);
    private final ReleaseSignature signature = mock(ReleaseSignature.class);
    private UpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app.deb", exchange -> respond(exchange, INSTALLER));
        server.createContext("/app.deb.sha256", exchange -> respond(exchange, (checksum + "  app.deb\n").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/app.deb.asc", exchange -> respond(exchange, "signature".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/repos/o/r/releases/latest", exchange -> respond(exchange, latestJson.getBytes(StandardCharsets.UTF_8)));
        server.start();
        when(signature.keyUsable()).thenReturn(true);
        when(updater.kind()).thenReturn(InstallKind.DEB);
        when(updater.supported(INSTALLATION)).thenReturn(true);
        service = service(Optional.of(INSTALLATION));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private UpdateService service(Optional<Installation> installation) {
        var properties = TestUpdateProperties.of("1.0.0", "o/r", base(), 6, 5, java.time.Duration.ofSeconds(5));
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(installation);
        return new UpdateService(properties, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector, () -> signature, new VerifiedDownload());
    }

    @Test
    void wrongChecksumFailsAndLeavesNoInstaller() {
        checksum = "0".repeat(64);

        assertThatThrownBy(() -> service.download(update(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void rightChecksumMovesTheInstallerAndRemovesOlderFilesAndFolders() throws Exception {
        checksum = goodChecksum();
        var updates = Files.createDirectories(dataDir.resolve("updates"));
        Files.writeString(updates.resolve("old-0.9.0.deb"), "old");
        Files.writeString(Files.createDirectories(updates.resolve("old-dir").resolve("nested")).resolve("f"), "old");

        var installer = service.download(update(), progress -> { });

        assertThat(installer).isEqualTo(updates.resolve("app.deb")).hasBinaryContent(INSTALLER);
        assertThat(updates).isDirectoryContaining(path -> path.equals(installer)).isDirectoryNotContaining(path -> path.getFileName().toString().startsWith("old"));
    }

    @Test
    void installLogsSurviveACleanupAndOnlyAnOversizedOneIsEmptied() throws Exception {
        checksum = goodChecksum();
        var updates = Files.createDirectories(dataDir.resolve("updates"));
        Files.writeString(updates.resolve("update.log"), "step install exit 1");
        Files.writeString(updates.resolve("update-msi.log"), "x".repeat((int) TestUpdateProperties.defaults().maxLogBytes() + 1));

        service.download(update(), progress -> { });

        assertThat(updates.resolve("update.log")).hasContent("step install exit 1");
        assertThat(updates.resolve("update-msi.log")).isEmptyFile();
    }

    @Test
    void anUnadvertisedInstallerSizeIsCappedByTheConfiguredMaximum() {
        checksum = "0".repeat(64);
        var capped = new UpdateProperties("1.0.0", "o/r", base(), 6, 5, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 1, 1,
                INSTALLER.length - 1, 1024, java.time.Duration.ofSeconds(30));
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(Optional.of(INSTALLATION));
        var small = new UpdateService(capped, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector, () -> signature, new VerifiedDownload());

        assertThatThrownBy(() -> small.download(update(0), progress -> { })).isInstanceOf(java.io.UncheckedIOException.class).hasMessageContaining("larger than the expected");
    }

    @Test
    void installerLargerThanTheAdvertisedSizeIsRejected() {
        checksum = "0".repeat(64);

        assertThatThrownBy(() -> service.download(update(INSTALLER.length - 1), progress -> { })).isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("larger than the expected");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void goodSignatureKeepsTheInstallerAndDropsTheSignatureFile() throws Exception {
        checksum = goodChecksum();

        var installer = service.download(update(), progress -> { });

        assertThat(installer).exists();
        assertThat(dataDir.resolve("updates")).isDirectoryNotContaining(path -> path.getFileName().toString().endsWith(".asc"));
        verify(signature).verify(any(), any());
    }

    @Test
    void badSignatureDeletesTheInstallerAndFailsClearly() throws Exception {
        checksum = goodChecksum();
        doThrow(new ReleaseSignature.InvalidSignatureException("no match")).when(signature).verify(any(), any());

        assertThatThrownBy(() -> service.download(update(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("signature")
                .hasMessageContaining("no match");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void unavailableSignatureFileDeletesTheInstaller() {
        checksum = goodChecksum();
        assertThatThrownBy(() -> service.download(brokenSignature(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("signature");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void anInstallableUpdateNeedsNoSignatureFlagFromTheUpdaterBecauseEveryOneIsVerified() {

        assertThat(service.canInstall(update())).isTrue();
        assertThat(service.canInstall(AvailableUpdate.releasePageOnly("1.0.1", URI.create("https://github.com/o/r/releases")))).isFalse();
        assertThatThrownBy(() -> service.download(AvailableUpdate.releasePageOnly("1.0.1", URI.create("https://github.com/o/r/releases")), progress -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be installed");
    }

    @Test
    void nothingIsInstallableWithoutASupportingUpdater() {
        when(updater.supported(INSTALLATION)).thenReturn(false);

        assertThat(service.canInstall(update())).isFalse();
        assertThat(service(Optional.empty()).canInstall(update())).isFalse();
    }

    @Test
    void theUpdatersSupportCheckRunsOncePerOfferedUpdate() {
        service.canInstall(update());
        service.needsAuthentication(update());
        service.canInstall(update());

        verify(updater, org.mockito.Mockito.times(1)).supported(INSTALLATION);
    }

    @Test
    void unclassifiedInstallationStillGetsTheReleasePageButNeverInstall() {
        var unclassified = service(Optional.empty());

        var found = unclassified.checkForUpdate();

        assertThat(found).hasValueSatisfying(update -> {
            assertThat(update.version()).isEqualTo("1.0.1");
            assertThat(update.releasePageUri()).isEqualTo(URI.create("https://github.com/o/r/releases/tag/v1.0.1"));
            assertThat(update.installable()).isFalse();
            assertThat(unclassified.canInstall(update)).isFalse();
            assertThat(unclassified.needsAuthentication(update)).isFalse();
        });
    }

    @Test
    void anExpiredOrRevokedReleaseKeyOffersOnlyTheReleasePageAndAUsableOneTheInstaller() {
        latestJson = releaseWithInstaller();

        assertThat(service.checkForUpdate()).hasValueSatisfying(found -> assertThat(found.installable()).isTrue());

        when(signature.keyUsable()).thenReturn(false);
        for (var i = 0; i < 2; i++) {
            assertThat(service.checkForUpdate()).hasValueSatisfying(found -> {
                assertThat(found.installable()).isFalse();
                assertThat(found.version()).isEqualTo("1.0.1");
                assertThat(found.releasePageUri()).isEqualTo(URI.create("https://github.com/o/r/releases/tag/v1.0.1"));
            });
        }
    }

    @Test
    void anUnreadableReleaseKeyAlsoOffersOnlyTheReleasePage() {
        latestJson = releaseWithInstaller();
        var properties = TestUpdateProperties.of("1.0.0", "o/r", base(), 6, 5, java.time.Duration.ofSeconds(5));
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(Optional.of(INSTALLATION));
        var broken = new UpdateService(properties, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector, () -> {
            throw new IllegalStateException("missing");
        }, new VerifiedDownload());

        assertThat(broken.checkForUpdate()).hasValueSatisfying(found -> assertThat(found.installable()).isFalse());
    }

    @Test
    void aSecondDownloadWhileOneRunsIsRefusedAndOneAfterItWorks() throws Exception {
        checksum = goodChecksum();
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var gate = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow.deb", exchange -> {
            arrived.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, INSTALLER);
        });
        server.createContext("/slow.deb.sha256", exchange -> respond(exchange, (checksum + "  slow.deb\n").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/slow.deb.asc", exchange -> respond(exchange, "signature".getBytes(StandardCharsets.UTF_8)));
        var slow = new AvailableUpdate("1.0.1", "slow.deb", INSTALLER.length, URI.create(base() + "/slow.deb"), URI.create(base() + "/slow.deb.sha256"), URI.create(base()));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.download(slow, progress -> { }));
        assertThat(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.download(update(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
        gate.countDown();

        assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).exists();
        assertThat(service.download(update(), progress -> { })).exists();
    }

    @Test
    void aRunWithUpdatesOffLeavesTheInstalledAppsFailureResultInPlace() throws Exception {
        var updates = Files.createDirectories(dataDir.resolve("updates"));
        Files.writeString(updates.resolve("result"), "FAILED extract\n");
        var off = TestUpdateProperties.of("", "o/r", base(), 6, 5, java.time.Duration.ofSeconds(5));

        var service = new UpdateService(off, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), mock(InstallationDetector.class), () -> signature,
                new VerifiedDownload());

        assertThat(service.lastOutcome()).isEmpty();
        assertThat(updates.resolve("result")).exists();
    }

    private static String releaseWithInstaller() {
        var name = "astrolabe-studio-1.0.1-linux-x64.deb";
        var url = "https://github.com/o/r/releases/download/v1.0.1/" + name;
        return "{\"tag_name\":\"v1.0.1\",\"html_url\":\"https://github.com/o/r/releases/tag/v1.0.1\",\"assets\":["
                + "{\"name\":\"" + name + "\",\"size\":10,\"browser_download_url\":\"" + url + "\"},"
                + "{\"name\":\"" + name + ".sha256\",\"size\":10,\"browser_download_url\":\"" + url + ".sha256\"},"
                + "{\"name\":\"" + name + ".asc\",\"size\":10,\"browser_download_url\":\"" + url + ".asc\"}]}";
    }

    @Test
    void supportedInstallationWithoutAssetsGetsNoUpdate() {
        assertThat(service.checkForUpdate()).isEmpty();
    }

    @Test
    void packageUpdateAsksForAuthenticationOnlyWhenTheUpdaterSaysSo() {
        when(updater.needsAuthentication()).thenReturn(true);

        assertThat(service.needsAuthentication(update())).isTrue();
        assertThat(service.needsAuthentication(AvailableUpdate.releasePageOnly("1.0.1", URI.create("https://github.com/o/r/releases")))).isFalse();
    }

    @Test
    void installerChangedAfterDownloadIsRefusedBeforePreparing() throws Exception {
        checksum = goodChecksum();
        var installer = service.download(update(), progress -> { });
        Files.writeString(installer, "swapped-bytes-xx");

        assertThatThrownBy(() -> service.prepareInstall(installer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        verify(updater, never()).prepare(any(), any(), any());
    }

    @Test
    void fileThatThisRunDidNotVerifyIsRefused() throws Exception {
        var foreign = Files.writeString(dataDir.resolve("foreign.deb"), "x");

        assertThatThrownBy(() -> service.prepareInstall(foreign)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not verified");
    }

    @Test
    void verifiedDownloadIsPreparedThenLaunchedThroughTheUpdater() throws Exception {
        checksum = goodChecksum();
        var installer = service.download(update(), progress -> { });
        var staged = dataDir.resolve("updates").resolve("unpacked-1");
        when(updater.prepare(installer.toRealPath(), UpdateFiles.sha256(installer), INSTALLATION)).thenReturn(staged);

        var prepared = service.prepareInstall(installer);
        service.launchAfterExit(prepared);

        verify(updater).launchAfterExit(staged, INSTALLATION);
    }

    @Test
    void launchRefusesAPreparedUpdateItDidNotMake() {
        var forged = new PreparedUpdate(Path.of("a"), Path.of("b"));

        assertThatThrownBy(() -> service.launchAfterExit(forged)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not prepared");
        verify(updater, never()).launchAfterExit(any(), any());
    }

    @Test
    void discardRemovesStagingInsideTheUpdatesFolderButKeepsTheDownload() throws Exception {
        checksum = goodChecksum();
        var installer = service.download(update(), progress -> { });
        var staged = Files.createDirectories(dataDir.resolve("updates").resolve("unpacked-1").resolve("nested"));
        Files.writeString(staged.resolve("f"), "x");
        when(updater.prepare(any(), any(), any())).thenReturn(staged.getParent());

        service.discard(service.prepareInstall(installer));

        assertThat(staged.getParent()).doesNotExist();
        assertThat(installer).exists();
    }

    @Test
    void oversizedChecksumFileIsRefused() {
        checksum = "a".repeat(5000);

        assertThatThrownBy(() -> service.download(update(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum file is too large");
    }

    @Test
    void missingAdvertisedSizeStillDownloadsUnderTheDefaultCap() {
        checksum = goodChecksum();

        assertThat(service.download(update(0), progress -> { })).exists();
    }

    @Test
    void missingReleaseKeyFailsOnlyWhenAnUpdateIsVerified() {
        checksum = goodChecksum();
        var properties = TestUpdateProperties.of("1.0.0", "o/r", base(), 6, 5, java.time.Duration.ofSeconds(5));
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(Optional.of(INSTALLATION));
        var lazy = new UpdateService(properties, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector,
                () -> {
                    throw new IllegalStateException("The bundled release key is unusable: missing");
                }, new VerifiedDownload());

        assertThat(lazy.canInstall(update())).isTrue();
        assertThatThrownBy(() -> lazy.download(update(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("release key is unusable");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void readsAndClearsThePreviousResultOnce() throws Exception {
        var updates = Files.createDirectories(dataDir.resolve("updates"));
        Files.writeString(updates.resolve("result"), "FAILED extract\n");

        var again = service(Optional.of(INSTALLATION));

        assertThat(again.lastOutcome()).contains(new UpdateOutcome(UpdateOutcome.Status.FAILED, "extract"));
        assertThat(updates.resolve("result")).doesNotExist();
        assertThat(service(Optional.of(INSTALLATION)).lastOutcome()).isEmpty();
    }

    @Test
    void parsesTheResultCodes() {
        assertThat(UpdateService.parseOutcome("OK")).contains(new UpdateOutcome(UpdateOutcome.Status.OK, ""));
        assertThat(UpdateService.parseOutcome("AUTH_CANCELLED")).contains(new UpdateOutcome(UpdateOutcome.Status.AUTH_CANCELLED, ""));
        assertThat(UpdateService.parseOutcome("garbage")).isEmpty();
    }

    @Test
    void failedStepIsShownOnlyWhenItIsAPlainName() {
        assertThat(UpdateService.parseOutcome("FAILED install-pkg")).contains(new UpdateOutcome(UpdateOutcome.Status.FAILED, "install-pkg"));
        assertThat(UpdateService.parseOutcome("FAILED <b>click</b> here")).contains(new UpdateOutcome(UpdateOutcome.Status.FAILED, ""));
        assertThat(UpdateService.parseOutcome("FAILED " + "a".repeat(33))).contains(new UpdateOutcome(UpdateOutcome.Status.FAILED, ""));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String goodChecksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(INSTALLER));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AvailableUpdate update() {
        return update(INSTALLER.length);
    }

    private AvailableUpdate update(long sizeBytes) {
        var base = base();
        return new AvailableUpdate("1.0.1", "app.deb", sizeBytes, URI.create(base + "/app.deb"), URI.create(base + "/app.deb.sha256"), URI.create(base));
    }

    /** The server has no .asc for this name, so the signature download answers 404. */
    private AvailableUpdate brokenSignature() {
        var base = base();
        server.createContext("/missing.deb", exchange -> respond(exchange, INSTALLER));
        server.createContext("/missing.deb.asc", exchange -> exchange.sendResponseHeaders(404, -1));
        server.createContext("/missing.deb.sha256", exchange -> respond(exchange, (checksum + "  missing.deb\n").getBytes(StandardCharsets.UTF_8)));
        return new AvailableUpdate("1.0.1", "missing.deb", INSTALLER.length, URI.create(base + "/missing.deb"), URI.create(base + "/missing.deb.sha256"), URI.create(base));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
