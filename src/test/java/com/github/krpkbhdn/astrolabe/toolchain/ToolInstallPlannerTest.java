package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolInstallPlannerTest {

    private static final List<String> NPM_INSTALL = List.of("npm", "install", "-g", "some tool");
    private static final DownloadSource DOWNLOAD = new DownloadSource(
            new LatestSource(LatestSource.Kind.TEXT_FILE, "https://download.eclipse.org/x/latest.txt", "v([0-9.]+)"),
            "https://download.eclipse.org/x/{version}/x.tar.gz", ArchiveKind.TAR_GZ, new LauncherSpec(LauncherSpec.Kind.BINARY, "bin/x"));

    private final ToolCatalog catalog = mock(ToolCatalog.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final HostEnvironment host = mock(HostEnvironment.class);
    private ManagedToolsDirectory directory;
    private ToolInstallPlanner planner;

    @BeforeEach
    void setUp(@TempDir Path dataDir) {
        directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        planner = new ToolInstallPlanner(catalog, probe, host, directory);
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.LINUX);
        register(tool("node", "Node.js", "", Map.of("linux", List.of("apt", "node")), Map.of(), null));
        status("node", ToolState.RESPONDING);
    }

    @Test
    void planDisplaysQuotedCommandLine() {
        var ts = tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL), Map.of(), null);

        assertThat(planner.plan(ts, false).commandLine()).isEqualTo("npm install -g \"some tool\"");
    }

    @Test
    void blocksWhenPrerequisiteIsMissing() {
        status("node", ToolState.MISSING);
        var ts = tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL), Map.of(), null);

        assertThat(planner.plan(ts, false).blocker()).contains("Install Node.js first");
    }

    @Test
    void freshPlanProbesThePrerequisiteWithoutTheCache() {
        when(probe.probe("node")).thenReturn(new ToolStatus("node", ToolState.MISSING, "", ""));
        when(probe.probeFresh("node")).thenReturn(new ToolStatus("node", ToolState.INSTALLED, "", ""));
        var ts = tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL), Map.of(), null);

        assertThat(planner.plan(ts, false).blocker()).isPresent();
        assertThat(planner.plan(ts, true).blocker()).isEmpty();
    }

    @Test
    void blocksWhenThereIsNoCommandForTheOs() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.MACOS);
        var ts = tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL), Map.of(), null);

        assertThat(planner.plan(ts, false).blocker()).contains("This tool cannot be installed automatically on macos.");
    }

    @Test
    void downloadPlanHasNoCommandAndNoBlocker() {
        var plan = planner.plan(downloadTool(), false);

        assertThat(plan.command()).isEmpty();
        assertThat(plan.commandLine()).isEmpty();
        assertThat(plan.blocker()).isEmpty();
        var download = plan.download().orElseThrow();
        assertThat(download.host()).isEqualTo("download.eclipse.org");
        assertThat(download.installDirectory()).isEqualTo(directory.root().resolve("dl"));
        assertThat(download.archiveUrlTemplate()).contains("{version}");
    }

    @Test
    void downloadForAnotherOsOnlyBlocksWithTheNoCommandReason() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.MACOS);

        var plan = planner.plan(downloadTool(), false);

        assertThat(plan.download()).isEmpty();
        assertThat(plan.blocker()).contains("This tool cannot be installed automatically on macos.");
    }

    @Test
    void manualInstallIsShownInThePlanButHasNoCommand() {
        var manual = tool("manual", "Manual", "", Map.of(), Map.of("linux", "curl https://x | sudo sh"), null);

        var plan = planner.plan(manual, false);

        assertThat(plan.manualCommand()).isEqualTo("curl https://x | sudo sh");
        assertThat(plan.command()).isEmpty();
        assertThat(plan.blocker()).contains("This tool cannot be installed automatically on linux.");
    }

    @Test
    void planWithoutManualInstallHasEmptyManualCommand() {
        var ts = tool("ts", "TypeScript", "node", Map.of("linux", NPM_INSTALL), Map.of(), null);

        assertThat(planner.plan(ts, false).manualCommand()).isEmpty();
    }

    @Test
    void osKeyFollowsTheHost() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.WINDOWS);

        assertThat(planner.osKey()).isEqualTo("windows");
    }

    private ToolDefinition downloadTool() {
        return tool("dl", "Downloaded", "", Map.of(), Map.of(), Map.of("linux", DOWNLOAD));
    }

    private void register(ToolDefinition tool) {
        when(catalog.find(tool.id())).thenReturn(Optional.of(tool));
    }

    private void status(String id, ToolState state) {
        var status = new ToolStatus(id, state, "", "");
        when(probe.probe(id)).thenReturn(status);
        when(probe.probeFresh(id)).thenReturn(status);
    }

    private static ToolDefinition tool(String id, String name, String prerequisite, Map<String, List<String>> install,
                                       Map<String, String> manual, Map<String, DownloadSource> download) {
        return new ToolDefinition(id, name, "g", ToolKind.LANGUAGE_SERVER, id, List.of(), "", prerequisite, List.of(), install,
                "https://docs", manual, download);
    }
}
