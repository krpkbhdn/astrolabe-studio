package com.github.krpkbhdn.astrolabe.voice.asset;

/**
 * A downloadable file with a pinned checksum; an archive is unpacked into relativePath after download.
 * A non-empty {@code archiveEntry} unpacks only that zip entry instead of the whole archive.
 */
public record VoiceAsset(String name, String url, String sha256, long sizeBytes, String relativePath, boolean archive,
        String archiveEntry) {

    public static final VoiceAsset SILERO_VAD = new VoiceAsset(
            "Silero VAD v5.1.2",
            "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v5.1.2.bin",
            "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf",
            885098L,
            "ggml-silero-v5.1.2.bin",
            false);

    public VoiceAsset(String name, String url, String sha256, long sizeBytes, String relativePath, boolean archive) {
        this(name, url, sha256, sizeBytes, relativePath, archive, "");
    }
}
