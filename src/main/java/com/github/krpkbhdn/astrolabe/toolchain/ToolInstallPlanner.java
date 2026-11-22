package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.DownloadPlan;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Builds the install plan of a tool for the host OS and decides whether it is blocked; runs nothing. */
final class ToolInstallPlanner {

    private final ToolCatalog catalog;
    private final ToolProbe probe;
    private final HostEnvironment host;
    private final ManagedToolsDirectory directory;

    ToolInstallPlanner(ToolCatalog catalog, ToolProbe probe, HostEnvironment host, ManagedToolsDirectory directory) {
        this.catalog = catalog;
        this.probe = probe;
        this.host = host;
        this.directory = directory;
    }

    InstallPlan plan(ToolDefinition tool, boolean fresh) {
        var os = osKey();
        var command = tool.installCommand(os);
        var download = tool.downloadSource(os).map(source -> downloadPlan(tool, source));
        return new InstallPlan(tool, command, commandLine(command), blocker(tool, os, fresh),
                tool.manualCommand(os), download);
    }

    String osKey() {
        return switch (host.os()) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
        };
    }

    private DownloadPlan downloadPlan(ToolDefinition tool, DownloadSource source) {
        return new DownloadPlan(URI.create(source.archiveUrl().replace("{version}", "1")).getHost(), source.latest().url(),
                source.archiveUrl(), directory.root().resolve(tool.id()), source.launcher());
    }

    private Optional<String> blocker(ToolDefinition tool, String os, boolean fresh) {
        if (!tool.hasInstallCommand(os) && tool.downloadSource(os).isEmpty()) {
            return Optional.of("This tool cannot be installed automatically on " + os + ".");
        }
        if (tool.prerequisite().isEmpty()) {
            return Optional.empty();
        }
        var state = (fresh ? probe.probeFresh(tool.prerequisite()) : probe.probe(tool.prerequisite())).state();
        if (state == ToolState.INSTALLED || state == ToolState.RESPONDING) {
            return Optional.empty();
        }
        var name = catalog.find(tool.prerequisite()).map(ToolDefinition::name).orElse(tool.prerequisite());
        return Optional.of("Install " + name + " first");
    }

    private static String commandLine(List<String> argv) {
        return String.join(" ", argv.stream().map(argument -> argument.isEmpty() || argument.chars().anyMatch(Character::isWhitespace)
                ? "\"" + argument + "\"" : argument).toList());
    }
}
