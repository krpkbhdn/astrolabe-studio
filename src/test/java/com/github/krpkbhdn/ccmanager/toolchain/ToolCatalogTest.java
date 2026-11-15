package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolCatalogTest {

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void loadsCatalogInFileOrder() {
        assertThat(catalog.all()).extracting(ToolDefinition::id).startsWith("node", "rustup").contains("typescript", "angular");
    }

    @Test
    void findsByIdAndReadsInstallCommand() {
        var typescript = catalog.find("typescript").orElseThrow();

        assertThat(typescript.kind()).isEqualTo(ToolKind.LANGUAGE_SERVER);
        assertThat(typescript.installCommand("windows"))
                .containsExactly("npm", "install", "-g", "typescript-language-server", "typescript@6");
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void failsOnMissingResource() {
        assertThatThrownBy(() -> new ToolCatalog("/toolchain/absent.json")).isInstanceOf(IllegalStateException.class);
    }
}
