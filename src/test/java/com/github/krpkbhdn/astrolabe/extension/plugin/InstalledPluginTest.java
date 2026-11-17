package com.github.krpkbhdn.astrolabe.extension.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InstalledPluginTest {

    private static final Path PROJECT = Path.of("work", "project").toAbsolutePath();

    @Test
    void projectInstallationMatchesTheSameFolderInAnyForm() {
        var plugin = new InstalledPlugin("a@m", "1", ExtensionScope.PROJECT, true, null, PROJECT.resolve("x").resolve(".."));

        assertThat(plugin.isInstallationOf("a@m", ExtensionScope.PROJECT, PROJECT)).isTrue();
        assertThat(plugin.isInstallationOf("a@m", ExtensionScope.PROJECT, PROJECT.resolve("other"))).isFalse();
        assertThat(plugin.isInstallationOf("a@m", ExtensionScope.LOCAL, PROJECT)).isFalse();
    }

    @Test
    void userInstallationIgnoresTheFolder() {
        var plugin = new InstalledPlugin("a@m", "1", ExtensionScope.USER, true, null, null);

        assertThat(plugin.isInstallationOf("a@m", ExtensionScope.USER, null)).isTrue();
    }

    @Test
    void missingFolderOnlyEqualsAMissingFolder() {
        assertThat(InstalledPlugin.sameDirectory(null, null)).isTrue();
        assertThat(InstalledPlugin.sameDirectory(PROJECT, null)).isFalse();
    }
}
