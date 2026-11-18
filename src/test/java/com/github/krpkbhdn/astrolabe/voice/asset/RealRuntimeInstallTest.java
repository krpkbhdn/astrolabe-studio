package com.github.krpkbhdn.astrolabe.voice.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.download.VerifiedDownload;
import com.github.krpkbhdn.astrolabe.voice.VoiceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in (ASTROLABE_REAL_RUNTIME_INSTALL=1): installs the pinned Linux and macOS archives (about 67 MB) by the app's own path; nothing is loaded. */
class RealRuntimeInstallTest {

    private static final VoiceProperties PROPERTIES = new VoiceProperties("en", Duration.ofSeconds(10),
            Duration.ofSeconds(1), Duration.ofSeconds(1), 0.01, 0.5, 4, Duration.ofSeconds(30), Duration.ofMinutes(5),
            Duration.ofMinutes(2), Duration.ofSeconds(60));

    @TempDir
    Path data;

    @Test
    void installsLinuxAndMacArchivesWithOnlyLibraries() throws Exception {
        assumeTrue("1".equals(System.getenv("ASTROLABE_REAL_RUNTIME_INSTALL")), "set ASTROLABE_REAL_RUNTIME_INSTALL=1 to run");
        var assets = new VoiceAssetService(new AppProperties(data), new VerifiedDownload(), PROPERTIES);
        for (var build : new RuntimeBuild[] {RuntimeBuild.LINUX_X64_CPU, RuntimeBuild.LINUX_ARM64_CPU, RuntimeBuild.MACOS_UNIVERSAL}) {
            var installed = assets.install(build.asset(), progress -> { });
            var libraries = build.libraryDirectory(installed);
            assertThat(build.isCompleteIn(libraries)).as(build.name()).isTrue();
            try (Stream<Path> all = Files.walk(installed)) {
                assertThat(all.filter(path -> !path.equals(installed)).toList()).as(build.name()).allSatisfy(path -> {
                    assertThat(Files.isSymbolicLink(path)).isFalse();
                    assertThat(Files.isRegularFile(path)).isTrue();
                    assertThat(path.getFileName().toString()).matches("lib[\\w.-]+\\.so[\\d.]*|whisper");
                });
            }
            System.out.println("INSTALLED " + build + " -> " + listing(libraries));
        }
    }

    private static String listing(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName() + ":" + path.toFile().length()).sorted().toList().toString();
        }
    }
}
