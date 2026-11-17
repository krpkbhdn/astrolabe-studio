package com.github.krpkbhdn.ccmanager.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OverlayAgentTest {

    @Test
    void aFoldedDescriptionIsJoinedNotCut() {
        var agent = OverlayAgent.parse("a.md", "---\nname: a\ndescription: >\n  Reviews code\n  and tests.\ntools: Read\n---\nBody").orElseThrow();

        assertThat(agent.description()).isEqualTo("Reviews code and tests.");
        assertThat(agent.tools()).isEqualTo("Read");
        assertThat(agent.prompt()).isEqualTo("Body");
    }

    @Test
    void aWrappedQuotedDescriptionIsJoinedAndUnquoted() {
        var agent = OverlayAgent.parse("a.md", "---\ndescription: \"one\n  two\"\n---\nBody").orElseThrow();

        assertThat(agent.description()).isEqualTo("one two");
    }
}
