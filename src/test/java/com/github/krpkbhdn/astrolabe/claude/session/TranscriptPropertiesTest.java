package com.github.krpkbhdn.astrolabe.claude.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TranscriptPropertiesTest {

    @Test
    void rejectsNonPositiveValuesNamingTheProperty() {
        assertThatThrownBy(() -> new TranscriptProperties(0, 1, 1, 1)).hasMessageContaining("max-entries");
        assertThatThrownBy(() -> new TranscriptProperties(1, 0, 1, 1)).hasMessageContaining("tail-bytes");
        assertThatThrownBy(() -> new TranscriptProperties(1, 1, 0, 1)).hasMessageContaining("refresh-tail-bytes");
        assertThatThrownBy(() -> new TranscriptProperties(1, 1, 1, 0)).hasMessageContaining("window-bytes");
    }
}
