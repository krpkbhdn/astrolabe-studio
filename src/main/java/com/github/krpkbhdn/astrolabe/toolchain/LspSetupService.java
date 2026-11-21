package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Kind;
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Outcome;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Sets up everything the language servers of a project need in one go: prerequisites, servers, then their Claude plugins. */
@Service
public class LspSetupService {

    private static final String CANCELLED = "Cancelled";
    private static final String ALREADY_INSTALLED = "already installed";

    private final ProjectToolsService projectTools;
    private final ToolInstallService installer;
    private final ToolProbe probe;
    private volatile boolean cancelled;

    public LspSetupService(ProjectToolsService projectTools, ToolInstallService installer, ToolProbe probe) {
        this.projectTools = projectTools;
        this.installer = installer;
        this.probe = probe;
    }

    /** Steps in run order for what is detected and not set up yet; nothing already installed or enabled appears. */
    public List<LspSetupStep> plan(Path projectDir) {
        var detected = projectTools.rows(projectDir).stream().filter(ProjectToolRow::detected).toList();
        var missing = detected.stream().filter(row -> row.status().state() == ToolState.MISSING).toList();
        var prerequisites = new LinkedHashSet<String>();
        for (var row : missing) {
            var prerequisite = row.tool().prerequisite();
            if (!prerequisite.isEmpty() && probe.probe(prerequisite).state() == ToolState.MISSING) {
                prerequisites.add(prerequisite);
            }
        }
        var steps = new ArrayList<LspSetupStep>();
        prerequisites.forEach(id -> steps.add(installStep(Kind.PREREQUISITE, id)));
        missing.forEach(row -> steps.add(installStep(Kind.SERVER, row.tool().id())));
        if (projectTools.inheritsLsp(projectDir)) {
            return List.copyOf(steps);
        }
        detected.stream()
                .filter(row -> !row.tool().lspPlugin().isEmpty()
                        && !row.pluginEnabled() && !row.userLevelEnabled())
                .forEach(row -> steps.add(new LspSetupStep(Kind.PLUGIN, row.tool().id(), Outcome.PENDING,
                        "Enable the " + row.tool().name() + " plugin")));
        return List.copyOf(steps);
    }

    /** Runs the pending steps in order; a failing step is reported and the rest still run, unless cancelled (the flag is cleared when a run starts, so {@link #plan} calls during a run never lose a cancel). */
    public List<LspSetupStep> run(Path projectDir, List<LspSetupStep> plan, Consumer<String> log) {
        cancelled = false;
        var result = new ArrayList<LspSetupStep>();
        for (var step : plan) {
            if (step.outcome() != Outcome.PENDING) {
                result.add(step);
            } else if (cancelled) {
                result.add(step.with(Outcome.SKIPPED, CANCELLED));
            } else {
                result.add(runStep(projectDir, step, log));
            }
        }
        return List.copyOf(result);
    }

    /** Stops the install that is running and skips the steps after it. */
    public void cancel() {
        cancelled = true;
        installer.cancel();
    }

    private LspSetupStep runStep(Path projectDir, LspSetupStep step, Consumer<String> log) {
        try {
            if (step.kind() == Kind.PLUGIN) {
                projectTools.enableLsp(projectDir, step.toolId());
                return step.with(Outcome.DONE, "Plugin enabled");
            }
            return install(step, log);
        } catch (RuntimeException e) {
            return step.with(Outcome.FAILED, String.valueOf(e.getMessage()));
        }
    }

    private LspSetupStep install(LspSetupStep step, Consumer<String> log) {
        var state = probe.probe(step.toolId()).state();
        if (state == ToolState.INSTALLED || state == ToolState.RESPONDING) {
            return step.with(Outcome.SKIPPED, ALREADY_INSTALLED);
        }
        var blocker = installer.plan(step.toolId()).flatMap(InstallPlan::blocker);
        if (blocker.isPresent()) {
            return step.with(Outcome.FAILED, blocker.get());
        }
        var result = installer.install(step.toolId(), log);
        return step.with(result.outcome() == ToolInstallService.Outcome.INSTALLED ? Outcome.DONE : Outcome.FAILED, result.message());
    }

    private LspSetupStep installStep(Kind kind, String toolId) {
        var plan = installer.plan(toolId);
        if (plan.isEmpty() || plan.get().command().isEmpty() && plan.get().download().isEmpty()) {
            var how = plan.map(InstallPlan::manualCommand).filter(command -> !command.isEmpty())
                    .map(command -> "Install it by hand: " + command).orElse("Install it by hand");
            return new LspSetupStep(kind, toolId, Outcome.MANUAL, how);
        }
        return new LspSetupStep(kind, toolId, Outcome.PENDING, "Install " + plan.get().tool().name());
    }
}
