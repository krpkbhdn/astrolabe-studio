package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LspHealthPropertiesTest {

    private static final Duration ONE = Duration.ofSeconds(1);

    @Test
    void defaultsAreBoundWhenNothingIsConfigured() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("astrolabe.toolchain.health", LspHealthProperties.class);

        assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.recoveryAttempts()).isEqualTo(2);
        assertThat(properties.recoveryBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.reinstallCooldown()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.markerRecheck()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.markerWait()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void everyValueMustBePositive() {
        assertThatThrownBy(() -> new LspHealthProperties(Duration.ZERO, 1, ONE, ONE, ONE, ONE)).hasMessageContaining("interval");
        assertThatThrownBy(() -> new LspHealthProperties(ONE, 0, ONE, ONE, ONE, ONE)).hasMessageContaining("recovery-attempts");
        assertThatThrownBy(() -> new LspHealthProperties(ONE, 1, Duration.ofSeconds(-1), ONE, ONE, ONE)).hasMessageContaining("recovery-backoff");
        assertThatThrownBy(() -> new LspHealthProperties(ONE, 1, ONE, Duration.ZERO, ONE, ONE)).hasMessageContaining("reinstall-cooldown");
        assertThatThrownBy(() -> new LspHealthProperties(ONE, 1, ONE, ONE, Duration.ZERO, ONE)).hasMessageContaining("marker-recheck");
        assertThatThrownBy(() -> new LspHealthProperties(ONE, 1, ONE, ONE, ONE, Duration.ZERO)).hasMessageContaining("marker-wait");
    }
}
