package com.github.krpkbhdn.astrolabe.claude.hook;

import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.MAPPER;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.OVERLAY_PROPERTIES;
import static com.github.krpkbhdn.astrolabe.claude.hook.HookTestFixtures.agentMarkdown;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.overlay.OverlayAgent;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlaySessionFilesTest {

    private OverlaySessionFiles files;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        files = new OverlaySessionFiles(MAPPER, HookTestFixtures.sessionPaths(dataDir), OVERLAY_PROPERTIES.maxAgentsJsonChars());
    }

    @Test
    void quoteHeavyOverlayAgentsCountEscapedCharactersSoTheyFallBackToThePlugin() throws Exception {
        var quoted = OverlayAgent.parse("q.md", agentMarkdown("\"".repeat(8_000))).orElseThrow();

        var agentsPlugin = files.writeAgentsPlugin("abc-1", HookTestFixtures.overlay(dataDir, "{}", "", "{}", java.util.List.of(quoted)), null);

        assertThat(com.github.krpkbhdn.astrolabe.team.TeamAgentsJson.agents(null, java.util.List.of(quoted)).length()).isLessThan(20_000);
        assertThat(agentsPlugin).exists();
    }

    @Test
    void noSkillsPluginWithoutOverlaySkills() throws Exception {
        assertThat(files.writeSkillsPlugin("abc-1", HookTestFixtures.overlay(dataDir, "{}", "", "{}", java.util.List.of()))).isNull();
    }

    @Test
    void smallOverlayAgentsStayInlineWithoutAPluginDirectory() throws Exception {
        var overlay = HookTestFixtures.overlay(dataDir, "{}", "", "{}", java.util.List.of(OverlayAgent.parse("small.md", agentMarkdown("ok")).orElseThrow()));

        assertThat(files.writeAgentsPlugin("abc-1", overlay, null)).isNull();
        assertThat(overlay.agents()).hasSize(1);
    }
}
