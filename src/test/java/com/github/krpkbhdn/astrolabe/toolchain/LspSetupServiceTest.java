package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Kind;
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupStep.Outcome;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan;
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LspSetupServiceTest {

    private static final Path DIR = Path.of("project");

    private final ProjectToolsService projectTools = mock(ProjectToolsService.class);
    private final ToolInstallService installer = mock(ToolInstallService.class);
    private final ToolProbe probe = mock(ToolProbe.class);
    private final LspSetupService service = new LspSetupService(projectTools, installer, probe);

    private final ToolDefinition node = tool("node", ToolKind.PREREQUISITE, "", "");
    private final ToolDefinition typescript = tool("typescript", ToolKind.LANGUAGE_SERVER, "ts-plugin", "node");
    private final ToolDefinition pyright = tool("pyright", ToolKind.LANGUAGE_SERVER, "", "node");
    private final ToolDefinition go = tool("go", ToolKind.LANGUAGE_SERVER, "go-plugin", "");

    @BeforeEach
    void everyToolIsMissingUnlessStubbed() {
        when(probe.probe(any())).thenAnswer(call -> new ToolStatus(call.getArgument(0), ToolState.MISSING, "", ""));
    }

    @Test
    void planOrdersPrerequisiteThenServersThenPluginsAndSharesOnePrerequisite() {
        rows(row(typescript, ToolState.MISSING, false), row(pyright, ToolState.MISSING, false));
        missing("node");
        installable(node, typescript, pyright);

        var plan = service.plan(DIR);

        assertThat(plan).extracting(LspSetupStep::kind, LspSetupStep::toolId)
                .containsExactly(tuple(Kind.PREREQUISITE, "node"),
                        tuple(Kind.SERVER, "typescript"),
                        tuple(Kind.SERVER, "pyright"),
                        tuple(Kind.PLUGIN, "typescript"));
        assertThat(plan).extracting(LspSetupStep::outcome).containsOnly(Outcome.PENDING);
    }

    @Test
    void planLeavesOutInstalledEnabledAndUndetectedTools() {
        rows(row(go, ToolState.INSTALLED, true), row(typescript, ToolState.MISSING, false, false));

        assertThat(service.plan(DIR)).isEmpty();
    }

    @Test
    void planSkipsPluginEnabledAtUserLevel() {
        rows(new ProjectToolRow(go, status(go, ToolState.INSTALLED), true, false, true));

        assertThat(service.plan(DIR)).isEmpty();
    }

    @Test
    void toolWithoutCommandForThisSystemIsManualAndNeverRun() {
        rows(row(go, ToolState.MISSING, true));
        when(installer.plan("go")).thenReturn(Optional.of(plan(go, List.of(), Optional.empty(), "brew install gopls")));

        var plan = service.plan(DIR);
        var result = service.run(DIR, plan, log -> {});

        assertThat(plan.getFirst().outcome()).isEqualTo(Outcome.MANUAL);
        assertThat(plan.getFirst().message()).contains("brew install gopls");
        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.MANUAL);
        verify(installer, never()).install(any(), any());
    }

    @Test
    void failureDoesNotStopTheRest() {
        var steps = List.of(pending(Kind.SERVER, "go"), pending(Kind.SERVER, "typescript"), pending(Kind.PLUGIN, "typescript"));
        installable(go, typescript);
        when(installer.install(eq("go"), any())).thenThrow(new IllegalStateException("boom"));
        when(installer.install(eq("typescript"), any())).thenReturn(installed("typescript"));

        var result = service.run(DIR, steps, log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.FAILED, Outcome.DONE, Outcome.DONE);
        assertThat(result.getFirst().message()).isEqualTo("boom");
        verify(projectTools).enableLsp(DIR, "typescript");
    }

    @Test
    void pluginNeedingConfirmationFailsWithItsText() {
        doThrow(new ExtensionException("needs your confirmation")).when(projectTools).enableLsp(DIR, "go");

        var result = service.run(DIR, List.of(pending(Kind.PLUGIN, "go")), log -> {});

        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.getFirst().message()).contains("needs your confirmation");
    }

    @Test
    void stillMissingInstallIsFailedWithItsExplanation() {
        installable(go);
        when(installer.install(eq("go"), any())).thenReturn(new InstallResult(ToolInstallService.Outcome.STILL_MISSING, 0,
                status(go, ToolState.MISSING), "Installed, but not on PATH"));

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "go")), log -> {});

        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.getFirst().message()).isEqualTo("Installed, but not on PATH");
    }

    @Test
    void serverBlockedByPrerequisiteIsPlannedAgainAndRunsOnceItIsInstalled() {
        var nodeInstalled = new java.util.concurrent.atomic.AtomicBoolean();
        var base = plan(typescript, List.of("npm"), Optional.empty(), "");
        installable(node);
        when(installer.plan("typescript")).thenAnswer(call ->
                Optional.of(nodeInstalled.get() ? base : blockedBy(base, "Install Node first")));
        when(installer.install(eq("node"), any())).thenAnswer(call -> {
            nodeInstalled.set(true);
            return installed("node");
        });
        when(installer.install(eq("typescript"), any())).thenReturn(installed("typescript"));

        var result = service.run(DIR, List.of(pending(Kind.PREREQUISITE, "node"), pending(Kind.SERVER, "typescript")), log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.DONE, Outcome.DONE);
    }

    @Test
    void blockedServerIsFailedWithTheBlocker() {
        when(installer.plan("typescript")).thenReturn(Optional.of(blockedBy(plan(typescript, List.of("npm"), Optional.empty(), ""), "Install Node first")));

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "typescript")), log -> {});

        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.getFirst().message()).isEqualTo("Install Node first");
        verify(installer, never()).install(any(), any());
    }

    @Test
    void cancelDelegatesAndSkipsTheRemainingSteps() {
        installable(go, typescript);
        when(installer.install(eq("go"), any())).thenAnswer(call -> {
            service.cancel();
            return new InstallResult(ToolInstallService.Outcome.FAILED, -1, status(go, ToolState.MISSING), "Install cancelled");
        });

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "go"), pending(Kind.SERVER, "typescript"), pending(Kind.PLUGIN, "go")), log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.FAILED, Outcome.SKIPPED, Outcome.SKIPPED);
        verify(installer).cancel();
        verify(installer, never()).install(eq("typescript"), any());
        verify(projectTools, never()).enableLsp(any(), any());
    }

    @Test
    void installOutputIsStreamedToTheLog() {
        installable(go);
        when(installer.install(eq("go"), any())).thenAnswer(call -> {
            call.<Consumer<String>>getArgument(1).accept("downloading");
            return installed("go");
        });
        var lines = new java.util.ArrayList<String>();

        service.run(DIR, List.of(pending(Kind.SERVER, "go")), lines::add);

        assertThat(lines).containsExactly("downloading");
    }

    @Test
    void prerequisiteWithoutCommandIsManualAndItsServerFailsOnItsBlocker() {
        rows(row(typescript, ToolState.MISSING, false));
        missing("node");
        when(installer.plan("node")).thenReturn(Optional.of(plan(node, List.of(), Optional.empty(), "brew install node")));
        when(installer.plan("typescript")).thenReturn(Optional.of(blockedBy(plan(typescript, List.of("npm"), Optional.empty(), ""), "Install Node first")));

        var plan = service.plan(DIR);
        var result = service.run(DIR, plan, log -> {});

        assertThat(plan.getFirst().outcome()).isEqualTo(Outcome.MANUAL);
        assertThat(result).extracting(LspSetupStep::outcome).startsWith(Outcome.MANUAL, Outcome.FAILED);
        verify(installer, never()).install(eq("node"), any());
    }

    @Test
    void failedPrerequisiteDoesNotStopLaterServersAndPlugins() {
        var steps = List.of(pending(Kind.PREREQUISITE, "node"), pending(Kind.SERVER, "go"), pending(Kind.PLUGIN, "go"));
        installable(node, go);
        when(installer.install(eq("node"), any())).thenReturn(new InstallResult(ToolInstallService.Outcome.FAILED, 1, status(node, ToolState.MISSING), "exit 1"));
        when(installer.install(eq("go"), any())).thenReturn(installed("go"));

        var result = service.run(DIR, steps, log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.FAILED, Outcome.DONE, Outcome.DONE);
    }

    @Test
    void cancelBeforeAPlanDoesNotSkipTheNextRunAndEmptyPlanRunsNothing() {
        service.cancel();
        rows();
        service.plan(DIR);
        installable(go);
        when(installer.install(eq("go"), any())).thenReturn(installed("go"));

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "go")), log -> {});

        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.DONE);
        assertThat(service.run(DIR, List.of(), log -> {})).isEmpty();
    }

    @Test
    void cancelDuringARunSurvivesAPlanCalledMeanwhile() {
        rows();
        installable(go, typescript);
        when(installer.install(eq("go"), any())).thenAnswer(call -> {
            service.cancel();
            service.plan(DIR);
            return installed("go");
        });

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "go"), pending(Kind.SERVER, "typescript")), log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.DONE, Outcome.SKIPPED);
        verify(installer, never()).install(eq("typescript"), any());
    }

    @Test
    void anInstallStepForAToolInstalledSinceThePlanIsSkipped() {
        installable(go);
        when(probe.probe("go")).thenReturn(status(go, ToolState.RESPONDING));

        var result = service.run(DIR, List.of(pending(Kind.SERVER, "go")), log -> {});

        assertThat(result.getFirst().outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(result.getFirst().message()).isEqualTo("already installed");
        verify(installer, never()).install(any(), any());
    }

    @Test
    void cancelDuringAPluginStepSkipsOnlyTheLaterSteps() {
        org.mockito.Mockito.doAnswer(call -> {
            service.cancel();
            return null;
        }).when(projectTools).enableLsp(DIR, "go");

        var result = service.run(DIR, List.of(pending(Kind.PLUGIN, "go"), pending(Kind.PLUGIN, "typescript")), log -> {});

        assertThat(result).extracting(LspSetupStep::outcome).containsExactly(Outcome.DONE, Outcome.SKIPPED);
    }

    private void rows(ProjectToolRow... rows) {
        when(projectTools.rows(DIR)).thenReturn(List.of(rows));
    }

    private void missing(String id) {
        when(probe.probe(id)).thenReturn(new ToolStatus(id, ToolState.MISSING, "", ""));
    }

    private void installable(ToolDefinition... tools) {
        for (var tool : tools) {
            when(installer.plan(tool.id())).thenReturn(Optional.of(plan(tool, List.of("npm", "i"), Optional.empty(), "")));
        }
    }

    private static InstallPlan plan(ToolDefinition tool, List<String> command, Optional<String> blocker, String manual) {
        return new InstallPlan(tool, command, String.join(" ", command), blocker, manual, Optional.empty());
    }

    private static InstallPlan blockedBy(InstallPlan plan, String blocker) {
        return new InstallPlan(plan.tool(), plan.command(), plan.commandLine(), Optional.of(blocker), "", Optional.empty());
    }

    private static InstallResult installed(String id) {
        return new InstallResult(ToolInstallService.Outcome.INSTALLED, 0, new ToolStatus(id, ToolState.INSTALLED, "1", ""), "Installed " + id);
    }

    private static LspSetupStep pending(Kind kind, String id) {
        return new LspSetupStep(kind, id, Outcome.PENDING, "");
    }

    private static ToolStatus status(ToolDefinition tool, ToolState state) {
        return new ToolStatus(tool.id(), state, "", "");
    }

    private static ProjectToolRow row(ToolDefinition tool, ToolState state, boolean pluginEnabled) {
        return row(tool, state, pluginEnabled, true);
    }

    private static ProjectToolRow row(ToolDefinition tool, ToolState state, boolean pluginEnabled, boolean detected) {
        return new ProjectToolRow(tool, status(tool, state), detected, pluginEnabled, false);
    }

    private static ToolDefinition tool(String id, ToolKind kind, String plugin, String prerequisite) {
        return new ToolDefinition(id, id, "group", kind, id, List.of(), plugin, prerequisite, List.of(), Map.of(), "", Map.of(), null);
    }
}
