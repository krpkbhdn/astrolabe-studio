package com.github.krpkbhdn.astrolabe.toolchain;

import static com.github.krpkbhdn.astrolabe.toolchain.ExecutableLookups.lookup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallRunner;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.Outcome;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandInstallTest {

    private static final List<String> NPM_INSTALL = List.of("npm", "install", "-g", "some tool");
    private static final ToolDefinition TS = new ToolDefinition("ts", "TypeScript", "g", ToolKind.LANGUAGE_SERVER, "ts", List.of(), "",
            "node", List.of(), Map.of("linux", NPM_INSTALL), "https://docs", Map.of(), null);
    private static final InstallPlan PLAN = new InstallPlan(TS, NPM_INSTALL, "", Optional.empty(), "", Optional.empty());

    private final ToolProbe probe = mock(ToolProbe.class);
    private final HostEnvironment host = mock(HostEnvironment.class);
    private final ToolchainProperties properties = mock(ToolchainProperties.class);
    private final List<List<String>> started = new ArrayList<>();
    private CommandRunner.Result result = new CommandRunner.Result(0, "out", "", false);
    private InstallRunner runner = (command, directory, timeout, environment, onOutput) -> {
        started.add(command);
        onOutput.accept("line 1");
        return result;
    };

    @BeforeEach
    void setUp() {
        when(host.os()).thenReturn(HostEnvironment.OperatingSystem.LINUX);
        when(host.home()).thenReturn(Path.of("home"));
        when(properties.installTimeout()).thenReturn(Duration.ofMinutes(5));
        when(probe.probeFresh("ts")).thenReturn(new ToolStatus("ts", ToolState.MISSING, "", ""));
    }

    @Test
    void windowsRunsThroughCmdWithQuotedArguments() {
        when(host.windows()).thenReturn(true);
        when(probe.probeFresh("ts")).thenReturn(new ToolStatus("ts", ToolState.INSTALLED, "", ""));

        commandInstall(lookup(host)).runCommand(PLAN, line -> {});

        assertThat(started).containsExactly(List.of("cmd.exe", "/c", "npm", "install", "-g", "\"some tool\""));
    }

    @Test
    void successStreamsOutputAndReprobes() {
        when(probe.probeFresh("ts")).thenReturn(new ToolStatus("ts", ToolState.RESPONDING, "5.0", ""));
        var lines = new ArrayList<String>();

        var installed = commandInstall(lookup(host)).runCommand(PLAN, lines::add);

        assertThat(installed.outcome()).isEqualTo(Outcome.INSTALLED);
        assertThat(lines).containsExactly("line 1");
        assertThat(started).containsExactly(NPM_INSTALL);
    }

    @Test
    void exitCodeZeroButMissingIsStillMissing() {
        var installed = commandInstall(lookup(host)).runCommand(PLAN, line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.STILL_MISSING);
        assertThat(installed.message()).isEqualTo("Installed, but not found on PATH. After a PATH change, restart the app so it is picked up.");
    }

    @Test
    void nonZeroExitFailsWithLastNonEmptyLine() {
        result = new CommandRunner.Result(1, "first\nnpm ERR! boom\n\n", "", false);

        var installed = commandInstall(lookup(host)).runCommand(PLAN, line -> {});

        assertThat(installed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(installed.exitCode()).isEqualTo(1);
        assertThat(installed.message()).isEqualTo("npm ERR! boom");
    }

    @Test
    void failureMessageSkipsRenderedSpinnerLines() {
        result = new CommandRunner.Result(1, "npm ERR! boom\n  ⠋ ⠙\n\n", "", false);

        assertThat(commandInstall(lookup(host)).runCommand(PLAN, line -> {}).message()).isEqualTo("npm ERR! boom");
    }

    @Test
    void installRunsInTheTempDirectoryNotHome() {
        var directories = new ArrayList<Path>();
        runner = (command, directory, timeout, environment, onOutput) -> {
            directories.add(directory);
            return result;
        };

        commandInstall(lookup(host)).runCommand(PLAN, line -> {});

        assertThat(directories).containsExactly(Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Test
    void installRunsWithTheLookupSearchPath() {
        var nodeDirectory = Path.of("pf", "nodejs");
        var windows = new FakeHostEnvironment(HostEnvironment.OperatingSystem.WINDOWS, Path.of("home")).withPath(Path.of("old"))
                .withEnv("ProgramFiles", "pf").withFile(nodeDirectory.resolve("node.exe"));
        var seen = new ArrayList<Map<String, String>>();
        runner = (command, directory, timeout, environment, onOutput) -> {
            seen.add(environment);
            return result;
        };

        commandInstall(lookup(windows)).runCommand(PLAN, line -> {});

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().get("PATH")).startsWith("old").contains(nodeDirectory.toString());
        assertThat(seen.getFirst()).containsEntry("NoDefaultCurrentDirectoryInExePath", "1");
        assertThat(seen.getFirst()).containsEntry("NONINTERACTIVE", "1");
    }

    private CommandInstall commandInstall(ExecutableLookup lookup) {
        return new CommandInstall(runner, lookup, host, properties, probe);
    }
}
