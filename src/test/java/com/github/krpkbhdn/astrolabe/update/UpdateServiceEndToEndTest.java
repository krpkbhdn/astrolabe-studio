package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.download.VerifiedDownload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** {@link UpdateService} against a local HTTP server, the real {@link ReleaseSignature} and {@link VerifiedDownload}, and real OpenPGP signatures. */
class UpdateServiceEndToEndTest {

    private static final String DEB = "astrolabe-studio-1.0.1-linux-x64.deb";
    private static final String GITHUB = "https://github.com/o/r/releases/download/v1.0.1/";
    private static final byte[] PACKAGE = "package-bytes".getBytes(StandardCharsets.UTF_8);
    private static final Installation INSTALLATION = new Installation(InstallKind.DEB, "x64", Path.of("root"), Path.of("root", "bin", "app"));

    @TempDir
    Path dataDir;

    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final PlatformUpdater updater = mock(PlatformUpdater.class);
    private HttpServer server;
    private PGPKeyPair releaseKey;
    private PGPKeyPair otherKey;
    private UpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        releaseKey = newKey();
        otherKey = newKey();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            } else {
                respond(exchange, body);
            }
        });
        server.start();
        when(updater.kind()).thenReturn(InstallKind.DEB);
        when(updater.supported(INSTALLATION)).thenReturn(true);
        when(updater.needsAuthentication()).thenReturn(true);
        service = service();
        servePackage(PACKAGE, hex(PACKAGE), sign(releaseKey, PACKAGE));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void foundSignedPackageReleaseIsInstallableAndNeedsAuthentication() {
        serveRelease(true);

        var update = service.checkForUpdate();

        assertThat(update).hasValueSatisfying(found -> {
            assertThat(found.installerName()).isEqualTo(DEB);
            assertThat(service.canInstall(found)).isTrue();
            assertThat(service.needsAuthentication(found)).isTrue();
        });
    }

    @Test
    void foundUnsignedPackageReleaseIsOnlyAPageLinkAndDownloadIsRefused() {
        serveRelease(false);

        var found = service.checkForUpdate().orElseThrow();

        assertThat(service.canInstall(found)).isFalse();
        assertThat(service.needsAuthentication(found)).isFalse();
        assertThatThrownBy(() -> service.download(found, progress -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(dataDir.resolve("updates")).doesNotExist();
    }

    @Test
    void goodSignatureAndChecksumYieldTheInstallerAndLeaveNoSignatureFile() throws Exception {
        var file = service.download(local(), progress -> { });

        assertThat(Files.readAllBytes(file)).isEqualTo(PACKAGE);
        try (var children = Files.list(file.getParent())) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly(DEB);
        }
    }

    @Test
    void signatureOfOtherContentIsRejectedAndTheInstallerDeleted() throws Exception {
        var tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        servePackage(tampered, hex(tampered), sign(releaseKey, PACKAGE));

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("signature");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void signatureFromAForeignKeyIsRejected() throws Exception {
        servePackage(PACKAGE, hex(PACKAGE), sign(otherKey, PACKAGE));

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("signature");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void anOversizedSignatureIsRejected() {
        servePackage(PACKAGE, hex(PACKAGE), new byte[70 * 1024]);

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void aBadChecksumFailsEvenWithAValidSignature() throws Exception {
        servePackage(PACKAGE, "0".repeat(64), sign(releaseKey, PACKAGE));

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void aPresentSignatureIsVerifiedEvenWhenTheUpdaterDoesNotRequireOne() throws Exception {
        servePackage(PACKAGE, hex(PACKAGE), sign(otherKey, PACKAGE));

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("signature");
        assertThat(dataDir.resolve("updates")).isEmptyDirectory();
    }

    @Test
    void aMismatchingChecksumIsRefusedEvenWithoutAnUpdaterSignatureRequirement() {
        servePackage(PACKAGE, "f".repeat(64), new byte[0]);

        assertThatThrownBy(() -> service.download(local(), progress -> { })).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum");
    }

    @Test
    void anUppercaseChecksumFileIsAccepted() throws Exception {
        servePackage(PACKAGE, hex(PACKAGE).toUpperCase(), sign(releaseKey, PACKAGE));

        assertThat(Files.readAllBytes(service.download(local(), progress -> { }))).isEqualTo(PACKAGE);
    }

    private void serveRelease(boolean withSignature) {
        var assets = new StringBuilder(asset(DEB) + "," + asset(DEB + ".sha256"));
        if (withSignature) {
            assets.append(",").append(asset(DEB + ".asc"));
        }
        served.put("/repos/o/r/releases/latest", ("{\"tag_name\":\"v1.0.1\",\"html_url\":\"https://github.com/o/r/releases/tag/v1.0.1\",\"assets\":[" + assets + "]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String asset(String name) {
        return "{\"name\":\"" + name + "\",\"size\":" + PACKAGE.length + ",\"browser_download_url\":\"" + GITHUB + name + "\"}";
    }

    private void servePackage(byte[] bytes, String checksum, byte[] signature) {
        served.put("/" + DEB, bytes);
        served.put("/" + DEB + ".sha256", (checksum + "  " + DEB + "\n").getBytes(StandardCharsets.UTF_8));
        served.put("/" + DEB + ".asc", signature);
    }

    /** The update as the parser would report it, but pointing at the local server because real assets must be on github.com. */
    private AvailableUpdate local() {
        var base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new AvailableUpdate("1.0.1", DEB, PACKAGE.length, URI.create(base + DEB), URI.create(base + DEB + ".sha256"), URI.create(base));
    }

    private UpdateService service() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var armor = new ArmoredOutputStream(out)) {
            releaseKey.getPublicKey().encode(armor);
        }
        var signature = new ReleaseSignature(new ByteArrayInputStream(out.toByteArray()), Clock.systemUTC());
        var properties = TestUpdateProperties.of("1.0.0", "o/r", "http://127.0.0.1:" + server.getAddress().getPort(), 6, 5, Duration.ofSeconds(5));
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(Optional.of(INSTALLATION));
        return new UpdateService(properties, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector, () -> signature, new VerifiedDownload());
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PGPKeyPair newKey() throws Exception {
        var generator = new Ed25519KeyPairGenerator();
        generator.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        return new BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, generator.generateKeyPair(), new Date());
    }

    private static byte[] sign(PGPKeyPair key, byte[] data) throws Exception {
        var generator = new PGPSignatureGenerator(new BcPGPContentSignerBuilder(key.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256), key.getPublicKey());
        generator.init(PGPSignature.BINARY_DOCUMENT, key.getPrivateKey());
        var subpackets = new PGPSignatureSubpacketGenerator();
        subpackets.setIssuerFingerprint(false, key.getPublicKey());
        subpackets.setIssuerKeyID(false, key.getKeyID());
        generator.setHashedSubpackets(subpackets.generate());
        generator.update(data);
        var out = new ByteArrayOutputStream();
        try (var armor = new ArmoredOutputStream(out)) {
            generator.generate().encode(armor);
        }
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
