package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseSignatureTest {

    private static final byte[] DATA = "release payload".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private PGPKeyPair key;
    private PGPKeyPair otherKey;
    private ReleaseSignature verifier;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        key = newKey();
        otherKey = newKey();
        ByteArrayOutputStream pub = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(pub)) {
            key.getPublicKey().encode(armor);
        }
        verifier = new ReleaseSignature(new ByteArrayInputStream(pub.toByteArray()), Clock.systemUTC());
        file = dir.resolve("app.bin");
        Files.write(file, DATA);
    }

    @Test
    void acceptsValidSignature() throws Exception {
        Path sig = sign(key, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256);
        assertThatCode(() -> verifier.verify(file, sig)).doesNotThrowAnyException();
    }

    @Test
    void refusesTamperedData() throws Exception {
        Path sig = sign(key, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256);
        Files.write(file, "tampered".getBytes(StandardCharsets.UTF_8));
        assertRefused(sig);
    }

    @Test
    void refusesSignatureFromDifferentKey() throws Exception {
        assertRefused(sign(otherKey, otherKey, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256));
    }

    @Test
    void refusesOtherKeyClaimingBundledIdentity() throws Exception {
        assertRefused(sign(otherKey, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256));
    }

    @Test
    void refusesGarbageArmor() throws Exception {
        Path sig = dir.resolve("garbage.asc");
        Files.writeString(sig, "-----BEGIN PGP SIGNATURE-----\n\n!!!!not base64\n-----END PGP SIGNATURE-----\n");
        assertRefused(sig);
    }

    @Test
    void refusesEmptySignatureFile() throws Exception {
        Path sig = dir.resolve("empty.asc");
        Files.write(sig, new byte[0]);
        assertRefused(sig);
    }

    @Test
    void refusesTrailingGarbage() throws Exception {
        Path sig = sign(key, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256);
        Files.writeString(sig, Files.readString(sig) + "junk", StandardOpenOption.TRUNCATE_EXISTING);
        assertRefused(sig);
    }

    @Test
    void refusesSha1() throws Exception {
        assertRefused(sign(key, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA1));
    }

    @Test
    void refusesTextDocumentSignature() throws Exception {
        assertRefused(sign(key, key, PGPSignature.CANONICAL_TEXT_DOCUMENT, HashAlgorithmTags.SHA256));
    }

    @Test
    void bundledKeyIsTheExpectedOne() throws IOException {
        assertThat(ReleaseSignature.bundled().fingerprint()).isEqualTo(ReleaseSignature.BUNDLED_KEY_FINGERPRINT);
    }

    @Test
    void aDifferentKeyThanTheBundledOneHasADifferentFingerprint() {
        assertThat(verifier.fingerprint()).isNotEqualTo(ReleaseSignature.BUNDLED_KEY_FINGERPRINT);
    }

    @Test
    void keyUsableTurnsFalseWhenTheBundledKeyExpiresAndVerificationRefuses() throws Exception {
        var pub = encoded(certified(key, true, 3600));
        var created = key.getPublicKey().getCreationTime().toInstant();

        var before = new ReleaseSignature(new ByteArrayInputStream(pub), Clock.fixed(created.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
        var after = new ReleaseSignature(new ByteArrayInputStream(pub), Clock.fixed(created.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        assertThat(before.keyUsable()).isTrue();
        assertThat(after.keyUsable()).isFalse();
        assertThatThrownBy(() -> after.verify(file, sign(key, key, PGPSignature.BINARY_DOCUMENT, HashAlgorithmTags.SHA256)))
                .isInstanceOf(ReleaseSignature.InvalidSignatureException.class).hasMessageContaining("expired");
    }

    @Test
    void aKeyWithoutExpiryStaysUsableAndARevokedOneIsNot() throws Exception {
        var far = Clock.fixed(Instant.now().plus(Duration.ofDays(36500)), ZoneOffset.UTC);

        assertThat(new ReleaseSignature(new ByteArrayInputStream(encoded(key.getPublicKey())), far).keyUsable()).isTrue();
        assertThat(new ReleaseSignature(new ByteArrayInputStream(encoded(certified(key, false, 0))), Clock.systemUTC()).keyUsable()).isFalse();
    }

    /** The public key with a self-signature: a key expiration time ({@code expires}) or, when not, a revocation. */
    private static PGPPublicKey certified(PGPKeyPair pair, boolean expires, long validSeconds) throws Exception {
        var gen = new PGPSignatureGenerator(new BcPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256), pair.getPublicKey());
        var sub = new PGPSignatureSubpacketGenerator();
        if (expires) {
            gen.init(PGPSignature.POSITIVE_CERTIFICATION, pair.getPrivateKey());
            sub.setKeyExpirationTime(true, validSeconds);
            gen.setHashedSubpackets(sub.generate());
            return PGPPublicKey.addCertification(pair.getPublicKey(), "release", gen.generateCertification("release", pair.getPublicKey()));
        }
        gen.init(PGPSignature.KEY_REVOCATION, pair.getPrivateKey());
        gen.setHashedSubpackets(sub.generate());
        return PGPPublicKey.addCertification(pair.getPublicKey(), gen.generateCertification(pair.getPublicKey()));
    }

    private static byte[] encoded(PGPPublicKey publicKey) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var armor = new ArmoredOutputStream(out)) {
            publicKey.encode(armor);
        }
        return out.toByteArray();
    }

    private void assertRefused(Path sig) {
        assertThatThrownBy(() -> verifier.verify(file, sig))
                .isInstanceOf(ReleaseSignature.InvalidSignatureException.class);
    }

    private static PGPKeyPair newKey() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        return new BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, pair, new Date());
    }

    // signer creates the signature; claimed is the key whose fingerprint and key ID it announces
    private Path sign(PGPKeyPair signer, PGPKeyPair claimed, int type, int hash) throws Exception {
        PGPSignatureGenerator gen = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(signer.getPublicKey().getAlgorithm(), hash), signer.getPublicKey());
        gen.init(type, signer.getPrivateKey());
        PGPSignatureSubpacketGenerator sub = new PGPSignatureSubpacketGenerator();
        sub.setIssuerFingerprint(false, claimed.getPublicKey());
        sub.setIssuerKeyID(false, claimed.getKeyID());
        gen.setHashedSubpackets(sub.generate());
        gen.update(DATA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(out)) {
            gen.generate().encode(armor);
        }
        Path sig = dir.resolve("sig-" + System.nanoTime() + ".asc");
        Files.write(sig, out.toByteArray());
        return sig;
    }
}
