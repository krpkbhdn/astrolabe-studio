package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCatalogContentTest {

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void containsEveryRequiredTechnology() {
        assertThat(catalog.all()).extracting(ToolDefinition::id).contains(
                "typescript", "css", "clangd", "jdtls", "pyright", "rust-analyzer", "gopls", "csharp-ls", "kotlin-lsp");
    }

    @Test
    void reactIsOnlyANoteOfTypescript() {
        assertThat(catalog.find("typescript").orElseThrow().includes()).containsExactly("React");
    }

    @Test
    void languageServersHaveKnownMarketplacePluginAndKnownPrerequisite() {
        var servers = catalog.all().stream().filter(tool -> tool.kind() == ToolKind.LANGUAGE_SERVER).toList();

        assertThat(servers).isNotEmpty().allSatisfy(server -> {
            assertThat(server.lspPlugin()).matches(".+@(claude-plugins-official|astrolabe-lsp)");
            assertThat(server.prerequisite().isEmpty()
                    || catalog.find(server.prerequisite()).filter(p -> p.kind() == ToolKind.PREREQUISITE).isPresent()).isTrue();
        });
    }

    @Test
    void bundledCssPluginLaunchesTheCatalogBinary() throws Exception {
        try (var in = getClass().getResourceAsStream("/lsp-marketplace/css-lsp/.claude-plugin/plugin.json")) {
            var plugin = tools.jackson.databind.json.JsonMapper.builder().build().readTree(in);

            assertThat(plugin.at("/lspServers/css/command").asText()).isEqualTo(catalog.find("css").orElseThrow().binary());
        }
    }

    @Test
    void bundledCssPluginIgnoresUnknownAtRulesAndMatchesMarketplaceVersion() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var plugin = getClass().getResourceAsStream("/lsp-marketplace/css-lsp/.claude-plugin/plugin.json");
                var market = getClass().getResourceAsStream("/lsp-marketplace/.claude-plugin/marketplace.json")) {
            var pluginJson = mapper.readTree(plugin);
            var marketJson = mapper.readTree(market);

            for (var language : List.of("css", "scss", "less")) {
                assertThat(pluginJson.at("/lspServers/css/settings/" + language + "/lint/unknownAtRules").asText())
                        .as(language).isEqualTo("ignore");
            }
            assertThat(pluginJson.at("/version").asText()).isEqualTo(marketJson.at("/plugins/0/version").asText());
        }
    }

    @Test
    void everyOsHasAnInstallCommandAManualCommandOrADocsPage() {
        for (var tool : catalog.all()) {
            for (var os : List.of("windows", "linux", "macos")) {
                assertThat(!tool.install().getOrDefault(os, List.of()).isEmpty()
                        || !tool.manualInstall().getOrDefault(os, "").isEmpty()
                        || !tool.docsUrl().isBlank())
                        .as("%s on %s", tool.id(), os).isTrue();
            }
        }
    }

    @Test
    void manualInstallCommandsAreSingleLines() {
        var lines = catalog.all().stream().flatMap(tool -> tool.manualInstall().values().stream()).toList();

        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line).isNotBlank().doesNotContain("\n", "\r"));
    }

    // pinned: typescript-language-server 6.x needs typescript 6; unpin when it supports 7
    @Test
    void typescriptPackageIsPinnedToMajor6() {
        var argvs = catalog.all().stream().flatMap(tool -> tool.install().values().stream())
                .filter(argv -> argv.stream().anyMatch(token -> token.startsWith("typescript"))).toList();

        assertThat(argvs).isNotEmpty().allSatisfy(argv -> assertThat(argv).contains("typescript@6").doesNotContain("typescript"));
    }

    private String firstGroup(String id, String os, String text) {
        var matcher = java.util.regex.Pattern.compile(catalog.find(id).orElseThrow().download().get(os).latest().pattern()).matcher(text);
        assertThat(matcher.find()).as("%s %s pattern on %s", id, os, text).isTrue();
        return matcher.group(1);
    }

    @Test
    void latestPatternsMatchTheRealPublishedStrings() {
        assertThat(firstGroup("jdtls", "windows", "jdt-language-server-1.64.0-202611121920.tar.gz\n".strip())).isEqualTo("1.64.0-202611121920");
        assertThat(firstGroup("jdtls", "linux", "jdt-language-server-1.64.0-202611121920.tar.gz")).isEqualTo("1.64.0-202611121920");
        assertThat(firstGroup("kotlin-lsp", "windows", "https://github.com/Kotlin/kotlin-lsp/releases/tag/kotlin-lsp/v271.2085.0"))
                .isEqualTo("271.2085.0");
        assertThat(firstGroup("kotlin-lsp", "linux", "https://github.com/Kotlin/kotlin-lsp/releases/tag/kotlin-lsp/v271.2085.0"))
                .isEqualTo("271.2085.0");
    }

    @Test
    void jdtlsPinsTheMilestoneAndKotlinLspHasNoPin() {
        var jdtls = catalog.find("jdtls").orElseThrow();

        for (var os : List.of("windows", "linux")) {
            var pinned = jdtls.downloadSource(os).orElseThrow().pinnedVersion().orElseThrow();
            assertThat(pinned.version()).isEqualTo("1.63.0-202610191322");
            assertThat(pinned.url()).isEqualTo("https://download.eclipse.org/jdtls/milestones/1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz");
            assertThat(pinned.sha256()).isEqualTo("b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4");
        }
        assertThat(jdtls.supportsChannels()).isTrue();
        assertThat(catalog.find("kotlin-lsp").orElseThrow().supportsChannels()).isFalse();
    }

    @Test
    void kotlinLspLauncherPassesItsStateDirectoryAsSystemPath() {
        var install = java.nio.file.Path.of("I");
        var windows = catalog.find("kotlin-lsp").orElseThrow().download().get("windows").launcher();
        var linux = catalog.find("kotlin-lsp").orElseThrow().download().get("linux").launcher();

        assertThat(windows.routed()).isTrue();
        assertThat(windows.path()).isEqualTo("bin/lsp-router.exe");
        assertThat(windows.argumentsFor(java.nio.file.Path.of("S"), install))
                .containsExactly("--server-launcher", install.resolve("bin/intellij-server.exe").toString(), "--system-path", "S")
                .doesNotContain("--stdio");
        assertThat(windows.fallback().argumentsFor(java.nio.file.Path.of("S"), install)).containsExactly("--system-path", "S");
        assertThat(linux.routed()).isFalse();
        assertThat(linux.argumentsFor(java.nio.file.Path.of("S"), install)).containsExactly("--system-path", "S");
    }

    @Test
    void catalogValidatesAndInstallsNeverEscalate() {
        ToolCatalogValidator.validate(catalog.all());

        var tokens = catalog.all().stream().flatMap(tool -> tool.install().values().stream()).flatMap(List::stream).toList();
        assertThat(tokens).noneMatch(token -> token.equalsIgnoreCase("sudo") || token.equalsIgnoreCase("runas"));
    }
}
