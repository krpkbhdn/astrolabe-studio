package com.github.krpkbhdn.astrolabe.toolchain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.plugin.Marketplace;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspMarketplacesTest {

    private static final String CSS_PLUGIN = "css-lsp@astrolabe-lsp";
    private static final String TS_PLUGIN = "typescript-lsp@claude-plugins-official";

    @TempDir
    Path dir;

    private final PluginService plugins = mock(PluginService.class);
    private final ClaudePluginWriter pluginWriter = mock(ClaudePluginWriter.class);
    private final LspMarketplaces marketplaces = new LspMarketplaces(plugins, pluginWriter);

    @BeforeEach
    void setUp() {
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("claude-plugins-official", "github", "x", "y")));
        when(pluginWriter.marketplaceDirectory()).thenReturn(dir.resolve("lsp-marketplace"));
    }

    @Test
    void missingOfficialMarketplaceIsAddedFirst() {
        when(plugins.marketplaces()).thenReturn(List.of());
        marketplaces.ensureMarketplace(TS_PLUGIN);
        verify(plugins).addMarketplace("anthropics/claude-plugins-official", ExtensionScope.USER, null);
    }

    @Test
    void knownOwnMarketplaceAtTheSameFolderIsNotAddedAgain() {
        var location = dir.resolve("lsp-marketplace").toAbsolutePath().toString();
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("astrolabe-lsp", "path", location, location)));
        marketplaces.ensureMarketplace(CSS_PLUGIN);
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void sameFolderSpelledDifferentlyIsNotAddedAgain() {
        var folder = dir.resolve("lsp-marketplace").toAbsolutePath().toString();
        var spelled = folder.toUpperCase(java.util.Locale.ROOT).replace('\\', '/') + "/";
        var other = org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs() ? spelled : folder + java.io.File.separator;
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("astrolabe-lsp", "path", other, other)));
        marketplaces.ensureMarketplace(CSS_PLUGIN);
        verify(plugins, never()).addMarketplace(any(), any(), any());
    }

    @Test
    void blankOrInvalidInstallLocationIsAddedAgainWithoutThrowing() {
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("astrolabe-lsp", "path", " ", " ")));
        marketplaces.ensureMarketplace(CSS_PLUGIN);
        verify(plugins).addMarketplace(dir.resolve("lsp-marketplace").toAbsolutePath().toString(), ExtensionScope.USER, null);
    }

    @Test
    void ownMarketplaceAtAnotherFolderIsAddedAgain() {
        when(plugins.marketplaces()).thenReturn(List.of(new Marketplace("astrolabe-lsp", "path", "old", "old")));
        marketplaces.ensureMarketplace(CSS_PLUGIN);
        verify(plugins).addMarketplace(dir.resolve("lsp-marketplace").toAbsolutePath().toString(), ExtensionScope.USER, null);
    }
}
