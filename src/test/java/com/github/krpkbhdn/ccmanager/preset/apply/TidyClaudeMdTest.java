package com.github.krpkbhdn.ccmanager.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TidyClaudeMdTest {

    @TempDir
    Path project;

    @Test
    void promptNamesTheMarkersAndAsksForAPlanFirst() {
        assertThat(TidyClaudeMd.prompt()).contains("cc-manager:begin", "Show me the plan first", "Never invent rules");
    }

    @Test
    void applicableOnlyWithAClaudeFile() throws IOException {
        assertThat(TidyClaudeMd.applicableTo(project)).isFalse();

        Files.writeString(project.resolve("CLAUDE.local.md"), "x");

        assertThat(TidyClaudeMd.applicableTo(project)).isTrue();
    }
}
