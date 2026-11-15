package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.CcManagerApplication;
import com.github.krpkbhdn.ccmanager.claude.launch.ClaudeLauncher;
import com.github.krpkbhdn.ccmanager.shell.ShellLauncher;
import com.github.krpkbhdn.ccmanager.voice.VoiceConfigurationBootTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class ToolchainWiringBootTest {

    @Test
    void contextWiresToolchainAndLaunchers(@TempDir Path dataDir) {
        var headless = System.getProperty("java.awt.headless");
        try (var context = new SpringApplicationBuilder(CcManagerApplication.class).web(WebApplicationType.NONE)
                .headless(true).run("--cc-manager.data-dir=" + dataDir.toAbsolutePath())) {
            assertThat(context.getBean(ToolInstallService.class)).isNotNull();
            assertThat(context.getBean(ExecutableLookup.class)).isNotNull();
            assertThat(context.getBean(ToolCatalog.class).all()).isNotEmpty();
            assertThat(context.getBean(ClaudeLauncher.class)).isNotNull();
            assertThat(context.getBean(ShellLauncher.class)).isNotNull();
        } finally {
            VoiceConfigurationBootTest.restoreHeadless(headless);
        }
    }
}
