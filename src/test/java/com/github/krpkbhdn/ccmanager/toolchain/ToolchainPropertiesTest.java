package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class ToolchainPropertiesTest {

    @Test
    void defaultsAreBoundWhenNothingIsConfigured() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("cc-manager.toolchain", ToolchainProperties.class);

        assertThat(properties.versionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.handshakeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.installTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.statusTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.downloadTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.maxDownloadSize()).isEqualTo(DataSize.ofGigabytes(1));
        assertThat(properties.maxUnpackedSize()).isEqualTo(DataSize.ofGigabytes(4));
        assertThat(properties.maxArchiveEntries()).isEqualTo(200_000);
        assertThat(properties.metadataTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void everyDurationMustBePositive() {
        var one = Duration.ofSeconds(1);

        assertThatThrownBy(() -> with(Duration.ZERO, one, one, one)).hasMessageContaining("version-timeout");
        assertThatThrownBy(() -> with(one, Duration.ofSeconds(-1), one, one)).hasMessageContaining("handshake-timeout");
        assertThatThrownBy(() -> with(one, one, Duration.ZERO, one)).hasMessageContaining("install-timeout");
        assertThatThrownBy(() -> with(one, one, one, Duration.ZERO)).hasMessageContaining("status-ttl");
    }

    @Test
    void downloadLimitsMustBePositive() {
        var one = Duration.ofSeconds(1);
        var size = DataSize.ofMegabytes(1);

        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, Duration.ZERO, one, size, size, 1, one, 5, 2000, 500))
                .hasMessageContaining("download-timeout");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, DataSize.ofBytes(0), size, 1, one, 5, 2000, 500))
                .hasMessageContaining("max-download-size");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, size, size, 0, one, 5, 2000, 500))
                .hasMessageContaining("max-archive-entries");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, size, size, 1, Duration.ZERO, 5, 2000, 500))
                .hasMessageContaining("metadata-timeout");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, size, size, 1, one, 0, 2000, 500))
                .hasMessageContaining("max-scan-depth");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, size, size, 1, one, 5, 0, 500))
                .hasMessageContaining("max-scan-folders");
        assertThatThrownBy(() -> new ToolchainProperties(one, one, one, one, one, one, size, size, 1, one, 5, 2000, 0))
                .hasMessageContaining("max-folder-entries");
    }

    private static ToolchainProperties with(Duration version, Duration handshake, Duration install, Duration ttl) {
        var size = DataSize.ofMegabytes(1);
        return new ToolchainProperties(version, handshake, install, ttl, Duration.ofSeconds(1), Duration.ofSeconds(1), size, size, 1, Duration.ofSeconds(1), 5, 2000, 500);
    }
}
