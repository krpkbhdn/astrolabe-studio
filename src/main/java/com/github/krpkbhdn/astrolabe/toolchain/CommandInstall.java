package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallResult;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallRunner;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.Outcome;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.WindowsArgument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Runs the catalog install argv of a tool in a PTY and turns the outcome into an InstallResult. */
final class CommandInstall {

    static final String PATH_RESTART_HINT = "Installed, but not found on PATH. After a PATH change, restart the app so it is picked up.";

    /** The temp folder is user-writable too, so cmd.exe is kept from searching it by NoDefaultCurrentDirectoryInExePath in the environment. */
    static final Path NEUTRAL_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));

    private final InstallRunner runner;
    private final ExecutableLookup lookup;
    private final HostEnvironment host;
    private final ToolchainProperties properties;
    private final ToolProbe probe;

    CommandInstall(InstallRunner runner, ExecutableLookup lookup, HostEnvironment host, ToolchainProperties properties,
                   ToolProbe probe) {
        this.runner = runner;
        this.lookup = lookup;
        this.host = host;
        this.properties = properties;
        this.probe = probe;
    }

    InstallResult runCommand(InstallPlan plan, Consumer<String> onOutput) {
        var toolId = plan.tool().id();
        CommandRunner.Result result;
        try {
            result = runner.run(launchCommand(plan.command()), NEUTRAL_DIRECTORY, properties.installTimeout(),
                    unattendedEnvironment(), onOutput);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                return cancelled(toolId);
            }
            return new InstallResult(Outcome.FAILED, -1, probe.probeFresh(toolId), "Could not run the install command: " + e.getMessage());
        }
        var status = probe.probeFresh(toolId);
        if (result.timedOut()) {
            return new InstallResult(Outcome.FAILED, result.exitCode(), status, "Install timed out after " + properties.installTimeout().toMinutes() + " min");
        }
        if (result.exitCode() != 0) {
            return new InstallResult(Outcome.FAILED, result.exitCode(), status, lastLine(result.stdout()));
        }
        if (status.state() == ToolState.MISSING) {
            return new InstallResult(Outcome.STILL_MISSING, 0, status, PATH_RESTART_HINT);
        }
        return new InstallResult(Outcome.INSTALLED, 0, status, "Installed " + plan.tool().name());
    }

    /** Probes without the interrupt flag, which a pending flag would abort, then restores it for the caller. */
    InstallResult cancelled(String toolId) {
        Thread.interrupted();
        var status = probe.probeFresh(toolId);
        Thread.currentThread().interrupt();
        return new InstallResult(Outcome.FAILED, -1, status, "Install cancelled");
    }

    /** On Windows npm, pnpm, dotnet and friends are .cmd shims, which only cmd.exe starts. */
    List<String> launchCommand(List<String> argv) {
        if (!host.windows()) {
            return argv;
        }
        var command = new ArrayList<String>(List.of("cmd.exe", "/c"));
        argv.forEach(argument -> command.add(WindowsArgument.quote(argument)));
        return command;
    }

    /** The install runs in a PTY nobody types into, so brew must never wait for a confirmation. */
    Map<String, String> unattendedEnvironment() {
        var environment = new LinkedHashMap<>(lookup.environment());
        environment.put("NONINTERACTIVE", "1");
        environment.put("HOMEBREW_NO_ENV_HINTS", "1");
        return environment;
    }

    /** The output is already rendered text; only a line with a letter or digit is a message (not a bar or spinner glyph). */
    static String lastLine(String output) {
        return output.lines().map(String::strip)
                .filter(line -> line.chars().anyMatch(Character::isLetterOrDigit)).reduce((first, second) -> second)
                .orElse("The install command failed");
    }
}
