package com.github.krpkbhdn.astrolabe.toolchain;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Timeouts of the language server toolchain checks and installs, how long a probed status is reused, and the limits of a downloaded tool archive. */
@ConfigurationProperties("astrolabe.toolchain")
public record ToolchainProperties(
        @DefaultValue("5s") Duration versionTimeout,
        @DefaultValue("10s") Duration handshakeTimeout,
        @DefaultValue("10m") Duration installTimeout,
        @DefaultValue("30s") Duration statusTtl,
        @DefaultValue("30m") Duration downloadTimeout,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("1GB") DataSize maxDownloadSize,
        @DefaultValue("4GB") DataSize maxUnpackedSize,
        @DefaultValue("200000") int maxArchiveEntries,
        @DefaultValue("30s") Duration metadataTimeout,
        @DefaultValue("5") int maxScanDepth,
        @DefaultValue("2000") int maxScanFolders,
        @DefaultValue("500") int maxFolderEntries) {

    public ToolchainProperties {
        positive("astrolabe.toolchain.version-timeout", versionTimeout);
        positive("astrolabe.toolchain.handshake-timeout", handshakeTimeout);
        positive("astrolabe.toolchain.install-timeout", installTimeout);
        positive("astrolabe.toolchain.status-ttl", statusTtl);
        positive("astrolabe.toolchain.download-timeout", downloadTimeout);
        positive("astrolabe.toolchain.connect-timeout", connectTimeout);
        positive("astrolabe.toolchain.max-download-size", maxDownloadSize.toBytes());
        positive("astrolabe.toolchain.max-unpacked-size", maxUnpackedSize.toBytes());
        positive("astrolabe.toolchain.max-archive-entries", maxArchiveEntries);
        positive("astrolabe.toolchain.metadata-timeout", metadataTimeout);
        positive("astrolabe.toolchain.max-scan-depth", maxScanDepth);
        positive("astrolabe.toolchain.max-scan-folders", maxScanFolders);
        positive("astrolabe.toolchain.max-folder-entries", maxFolderEntries);
    }
}
