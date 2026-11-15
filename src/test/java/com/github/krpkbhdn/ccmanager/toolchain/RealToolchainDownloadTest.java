package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.toolchain.managed.LauncherWriter;
import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.ccmanager.workspace.SystemHostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Real downloads of jdtls (~48 MB) and kotlin-lsp (~350 MB) into a temp data dir; run with CCM_REAL_TOOLCHAIN=1. */
@EnabledIfEnvironmentVariable(named = "CCM_REAL_TOOLCHAIN", matches = "1")
class RealToolchainDownloadTest {

    private static final List<String> TOOL_IDS = List.of("jdtls", "kotlin-lsp");

    @TempDir
    Path dataDir;

    @Test
    void installsProbesUpdatesAndUninstallsRealTools() throws IOException {
        var home = Path.of(System.getProperty("user.home"));
        var homeBefore = listing(home);
        var pathBefore = System.getenv("PATH");
        var properties = new ToolchainProperties(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(10), DataSize.ofGigabytes(1),
                DataSize.ofGigabytes(4), 200000, Duration.ofSeconds(30), 5, 2000, 500);
        var directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        var host = new SystemHostEnvironment();
        var catalog = new ToolCatalog();
        var lookup = new ExecutableLookup(host, directory);
        var probe = new ToolProbe(catalog, lookup, host, new LspHandshake(), properties, directory);
        var service = new ToolInstallService(catalog, probe, lookup, host, properties, directory, new LauncherWriter(directory));

        for (var id : TOOL_IDS) {
            var started = System.nanoTime();
            var first = service.install(id, text -> { });
            System.out.printf("%s install took %d s: %s%n", id, Duration.ofNanos(System.nanoTime() - started).toSeconds(), first.message());
            assertThat(first.outcome()).as(first.message()).isEqualTo(ToolInstallService.Outcome.INSTALLED);
            assertThat(service.isManaged(id)).isTrue();
            var status = probe.probeFresh(id);
            System.out.println(id + " status " + status);
            assertThat(status.state()).as(status.detail()).isEqualTo(ToolState.RESPONDING);

            var second = service.install(id, text -> { });
            assertThat(second.message()).contains("already up to date");

            var removed = service.uninstall(id);
            assertThat(removed.outcome()).as(removed.message()).isEqualTo(ToolInstallService.Outcome.INSTALLED);
            assertThat(directory.root().resolve(id)).doesNotExist();
            assertThat(service.isManaged(id)).isFalse();
        }
        try (Stream<Path> launchers = Files.exists(directory.binDirectory()) ? Files.list(directory.binDirectory()) : Stream.empty()) {
            assertThat(launchers.toList()).isEmpty();
        }
        assertThat(listing(home)).isEqualTo(homeBefore);
        assertThat(System.getenv("PATH")).isEqualTo(pathBefore);
    }

    private static List<String> listing(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.map(path -> root.relativize(path).toString()).sorted().toList();
        }
    }
}
