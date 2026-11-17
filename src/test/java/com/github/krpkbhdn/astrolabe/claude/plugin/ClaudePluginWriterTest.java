package com.github.krpkbhdn.astrolabe.claude.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudePluginWriterTest {

    @TempDir
    Path dataDir;

    @Test
    void copiesTheManifestAndSkillAndOverwritesStaleCopies() throws Exception {
        var writer = new ClaudePluginWriter(new AppProperties(dataDir));
        var skill = writer.directory().resolve("skills/run-configurations/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "stale");

        writer.write();

        assertThat(writer.directory()).isEqualTo(dataDir.resolve("claude-plugin"));
        assertThat(Files.readString(writer.directory().resolve(".claude-plugin/plugin.json"))).contains("\"name\": \"astrolabe\"");
        assertThat(Files.readString(skill)).startsWith("---").contains("name: run-configurations");
        assertThat(Files.readString(writer.directory().resolve("skills/visual-review/SKILL.md"))).startsWith("---").contains("name: visual-review");
    }

    @Test
    void writesTheLspMarketplaceWithItsCssPlugin() throws Exception {
        var writer = new ClaudePluginWriter(new AppProperties(dataDir));

        writer.write();

        assertThat(writer.marketplaceDirectory()).isEqualTo(dataDir.resolve("lsp-marketplace"));
        assertThat(Files.readString(writer.marketplaceDirectory().resolve(".claude-plugin/marketplace.json")))
                .contains("\"name\": \"" + ClaudePluginWriter.LSP_MARKETPLACE + "\"").contains("\"name\": \"css-lsp\"");
        assertThat(Files.readString(writer.marketplaceDirectory().resolve("css-lsp/.claude-plugin/plugin.json"))).contains("vscode-css-language-server");
    }
}
