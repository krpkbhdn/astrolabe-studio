package com.github.krpkbhdn.ccmanager.toolchain;

import static com.github.krpkbhdn.ccmanager.config.PropertyRules.positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Timeouts of the language server toolchain checks and installs, how long a probed status is reused, and the limits of a downloaded tool archive. */
@ConfigurationProperties("cc-manager.toolchain")
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
        positive("cc-manager.toolchain.version-timeout", versionTimeout);
        positive("cc-manager.toolchain.handshake-timeout", handshakeTimeout);
        positive("cc-manager.toolchain.install-timeout", installTimeout);
        positive("cc-manager.toolchain.status-ttl", statusTtl);
        positive("cc-manager.toolchain.download-timeout", downloadTimeout);
        positive("cc-manager.toolchain.connect-timeout", connectTimeout);
        positive("cc-manager.toolchain.max-download-size", maxDownloadSize.toBytes());
        positive("cc-manager.toolchain.max-unpacked-size", maxUnpackedSize.toBytes());
        positive("cc-manager.toolchain.max-archive-entries", maxArchiveEntries);
        positive("cc-manager.toolchain.metadata-timeout", metadataTimeout);
        positive("cc-manager.toolchain.max-scan-depth", maxScanDepth);
        positive("cc-manager.toolchain.max-scan-folders", maxScanFolders);
        positive("cc-manager.toolchain.max-folder-entries", maxFolderEntries);
    }
}
