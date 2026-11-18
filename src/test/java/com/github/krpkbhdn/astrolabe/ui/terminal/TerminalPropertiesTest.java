package com.github.krpkbhdn.astrolabe.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminalPropertiesTest {

    @Test
    void defaultScrollbackIsFiveThousandLines() {
        assertThat(TerminalProperties.DEFAULT_SCROLLBACK_LINES).isEqualTo(5000);
    }

    @Test
    void zeroScrollbackIsRejected() {
        assertThatThrownBy(() -> new TerminalProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
