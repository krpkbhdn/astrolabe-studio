package com.github.krpkbhdn.astrolabe.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.IssuerFingerprint;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * Verifies a detached armored OpenPGP signature of a file against the bundled release key.
 * Uses only BouncyCastle's lightweight Bc* API, so no security provider is registered.
 */
public final class ReleaseSignature {

    public static final String BUNDLED_KEY_RESOURCE = "/update/astrolabe-release.pub.asc";

    /** The largest signature file read; anything bigger is refused. */
    public static final int MAX_SIGNATURE_BYTES = 64 * 1024;

    /** Fingerprint of the release key bundled with the app (public); a different key in the resource is refused. */
    public static final String BUNDLED_KEY_FINGERPRINT = "8E6A49E974A189497BAA92FD8FE33C96E3DD8B8B";

    private static final int COPY_BUFFER = 64 * 1024;
    private static final Set<Integer> ACCEPTED_HASHES = Set.of(
            HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512);
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final PGPPublicKey primary;
    private final Map<String, PGPPublicKey> signingKeys = new HashMap<>();
    private final Clock clock;

    public ReleaseSignature(InputStream publicKey, Clock clock) throws IOException {
        this.clock = clock;
        try {
            PGPPublicKeyRingCollection rings = new BcPGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicKey));
            Iterator<PGPPublicKeyRing> it = rings.getKeyRings();
            if (!it.hasNext()) {
                throw new IOException("No public key in the key file");
            }
            PGPPublicKeyRing ring = it.next();
            if (it.hasNext()) {
                throw new IOException("The key file must hold exactly one key");
            }
            primary = ring.getPublicKey();
            for (PGPPublicKey key : ring) {
                if (key.isMasterKey() || canSign(key)) {
                    signingKeys.put(fingerprintOf(key), key);
                }
            }
        } catch (PGPException e) {
            throw new IOException("Unreadable public key", e);
        }
    }

    public static ReleaseSignature bundled() throws IOException {
        try (InputStream in = ReleaseSignature.class.getResourceAsStream(BUNDLED_KEY_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled release key is missing");
            }
            var bundled = new ReleaseSignature(in, Clock.systemUTC());
            if (!bundled.fingerprint().equals(BUNDLED_KEY_FINGERPRINT)) {
                throw new IOException("The bundled release key is not the expected one");
            }
            return bundled;
        }
    }

    public String fingerprint() {
        return fingerprintOf(primary);
    }

    public void verify(Path file, Path signature) throws IOException {
        PGPSignature sig = parse(signature);
        if (sig.getSignatureType() != PGPSignature.BINARY_DOCUMENT) {
            throw refuse("not a binary document signature");
        }
        if (!ACCEPTED_HASHES.contains(sig.getHashAlgorithm())) {
            throw refuse("weak or unsupported hash algorithm");
        }
        PGPPublicKey key = signerOf(sig);
        checkUsable(key);
        try (InputStream in = Files.newInputStream(file)) {
            sig.init(new BcPGPContentVerifierBuilderProvider(), key);
            byte[] buffer = new byte[COPY_BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                sig.update(buffer, 0, n);
            }
            if (!sig.verify()) {
                throw refuse("signature does not match the file");
            }
        } catch (PGPException e) {
            throw refuse("signature cannot be verified");
        }
    }

    private PGPSignature parse(Path signature) throws IOException {
        if (Files.size(signature) > MAX_SIGNATURE_BYTES) {
            throw refuse("signature file is too large");
        }
        byte[] bytes = Files.readAllBytes(signature);
        try (ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
                ArmoredInputStream armored = new ArmoredInputStream(raw)) {
            BcPGPObjectFactory factory = new BcPGPObjectFactory(armored);
            Object first = factory.nextObject();
            if (!(first instanceof PGPSignatureList list) || list.size() != 1) {
                throw refuse("expected exactly one signature");
            }
            if (factory.nextObject() != null) {
                throw refuse("unexpected data after the signature");
            }
            if (!new String(raw.readAllBytes(), StandardCharsets.ISO_8859_1).isBlank()) {
                throw refuse("trailing data after the signature");
            }
            return list.get(0);
        } catch (InvalidSignatureException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw refuse("unreadable signature");
        }
    }

    private PGPPublicKey signerOf(PGPSignature sig) {
        IssuerFingerprint issuer = sig.getHashedSubPackets().getIssuerFingerprint();
        if (issuer == null) {
            throw refuse("signature names no issuer fingerprint");
        }
        PGPPublicKey key = signingKeys.get(HEX.formatHex(issuer.getFingerprint()));
        if (key == null || key.getKeyID() != sig.getKeyID()) {
            throw refuse("signed by an unknown key");
        }
        return key;
    }

    /** True while at least one signing key of the bundled key is neither revoked nor expired at the clock's now; false means no signature can verify. */
    public boolean keyUsable() {
        return signingKeys.values().stream().anyMatch(key -> unusableReason(key).isEmpty());
    }

    private void checkUsable(PGPPublicKey key) {
        unusableReason(key).ifPresent(reason -> {
            throw refuse(reason);
        });
    }

    private Optional<String> unusableReason(PGPPublicKey key) {
        if (key.hasRevocation()) {
            return Optional.of("signing key is revoked");
        }
        long valid = key.getValidSeconds();
        if (valid > 0 && Instant.now(clock).isAfter(key.getCreationTime().toInstant().plusSeconds(valid))) {
            return Optional.of("signing key is expired");
        }
        return Optional.empty();
    }

    private static boolean canSign(PGPPublicKey subkey) {
        Iterator<PGPSignature> bindings = subkey.getSignaturesOfType(PGPSignature.SUBKEY_BINDING);
        while (bindings.hasNext()) {
            PGPSignature binding = bindings.next();
            if (binding.getHashedSubPackets() != null
                    && (binding.getHashedSubPackets().getKeyFlags() & KeyFlags.SIGN_DATA) != 0) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprintOf(PGPPublicKey key) {
        return HEX.formatHex(key.getFingerprint());
    }

    private static InvalidSignatureException refuse(String reason) {
        return new InvalidSignatureException(reason);
    }

    public static final class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String reason) {
            super(reason);
        }
    }
}
