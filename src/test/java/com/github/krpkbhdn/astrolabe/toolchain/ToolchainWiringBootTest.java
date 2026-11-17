package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.AstrolabeApplication;
import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher;
import com.github.krpkbhdn.astrolabe.shell.ShellLauncher;
import com.github.krpkbhdn.astrolabe.voice.VoiceConfigurationBootTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class ToolchainWiringBootTest {

    @Test
    void contextWiresToolchainAndLaunchers(@TempDir Path dataDir) {
        var headless = System.getProperty("java.awt.headless");
        try (var context = new SpringApplicationBuilder(AstrolabeApplication.class).web(WebApplicationType.NONE)
                .headless(true).run("--astrolabe.data-dir=" + dataDir.toAbsolutePath())) {
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
