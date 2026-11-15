package com.github.krpkbhdn.ccmanager.claude.plugin;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.stereotype.Component;

/** Copies the bundled cc-manager plugin (skills for Claude) and the local LSP marketplace from the classpath to the data directory on every start, so they never go stale. */
@Component
public class ClaudePluginWriter {

    /** Name of the marketplace that ships the language-server plugins Anthropic does not offer. */
    public static final String LSP_MARKETPLACE = "cc-manager-lsp";

    private static final String PLUGIN_RESOURCE_ROOT = "/claude-plugin/";
    private static final List<String> PLUGIN_FILES = List.of(".claude-plugin/plugin.json", "skills/run-configurations/SKILL.md",
            "skills/visual-review/SKILL.md");
    private static final String MARKETPLACE_RESOURCE_ROOT = "/lsp-marketplace/";
    private static final List<String> MARKETPLACE_FILES = List.of(".claude-plugin/marketplace.json",
            "css-lsp/.claude-plugin/plugin.json");

    private final Path directory;
    private final Path marketplaceDirectory;

    public ClaudePluginWriter(AppProperties properties) {
        this.directory = properties.dataDir().resolve("claude-plugin");
        this.marketplaceDirectory = properties.dataDir().resolve("lsp-marketplace");
    }

    /** The directory passed as claude --plugin-dir. */
    public Path directory() {
        return directory;
    }

    /** The directory added as the local plugin marketplace {@link #LSP_MARKETPLACE}. */
    public Path marketplaceDirectory() {
        return marketplaceDirectory;
    }

    @PostConstruct
    void write() {
        copy(PLUGIN_RESOURCE_ROOT, PLUGIN_FILES, directory);
        copy(MARKETPLACE_RESOURCE_ROOT, MARKETPLACE_FILES, marketplaceDirectory);
    }

    private static void copy(String resourceRoot, List<String> files, Path root) {
        for (var file : files) {
            var target = root.resolve(file);
            try (var in = ClaudePluginWriter.class.getResourceAsStream(resourceRoot + file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundled resource " + resourceRoot + file);
                }
                Files.createDirectories(target.getParent());
                var temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
                try {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write plugin file " + target, e);
            }
        }
    }
}
