package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CodeModePropertiesTest {

    @Test
    void rejectsNonPositiveLimitsAndBlankImage() {
        assertThatThrownBy(() -> properties(Duration.ZERO, "i")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(Duration.ofSeconds(1), "i").image()).isEqualTo("i");
    }

    private static CodeModeProperties properties(Duration timeout, String image) {
        return TestProperties.of(timeout, 1, 1, 1, Duration.ofMinutes(1), image, "1m", 1, Duration.ofDays(1), Duration.ofSeconds(25));
    }
}
