package com.github.krpkbhdn.astrolabe.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.AstrolabeApplication;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class VoiceConfigurationBootTest {

    @Test
    void contextCreatesVoiceBeans(@TempDir Path dataDir) {
        var headless = System.getProperty("java.awt.headless");
        try (var context = new SpringApplicationBuilder(AstrolabeApplication.class).web(WebApplicationType.NONE)
                .headless(true).run("--astrolabe.data-dir=" + dataDir.toAbsolutePath())) {
            assertThat(context.getBean(VoiceProperties.class).threads()).isPositive();
            assertThat(context.getBean(VoiceInputService.class)).isNotNull();
        } finally {
            restoreHeadless(headless);
        }
    }

    /** Spring sets the JVM-wide property, which would make later AWT tests in this fork headless. */
    public static void restoreHeadless(String previous) {
        if (previous == null) {
            System.clearProperty("java.awt.headless");
        } else {
            System.setProperty("java.awt.headless", previous);
        }
    }
}
