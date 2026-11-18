package com.github.krpkbhdn.astrolabe.preset.apply;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlayService;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Appliers over a real overlay folder in {@code dataDir} for one registered project. */
final class OverlayInstructions {

    private OverlayInstructions() {}

    static ProjectOverlaySettings settings(Path dataDir, long projectId, Path projectDir) {
        var projects = mock(ProjectService.class);
        when(projects.findByDirectory(projectDir)).thenReturn(java.util.Optional.of(new Project(projectId, "p", projectDir, Instant.now())));
        var overlays = new ProjectOverlayService(new AppProperties(dataDir), projects, mock(com.github.krpkbhdn.astrolabe.toolchain.GroupLspOverrides.class));
        return new ProjectOverlaySettings(new com.github.krpkbhdn.astrolabe.extension.ProjectOverlays(projects, overlays, mock(com.github.krpkbhdn.astrolabe.git.TaskWorktreeService.class)));
    }

    static InstructionApplier applier(Path dataDir, long projectId, Path projectDir) {
        return new InstructionApplier(settings(dataDir, projectId, projectDir));
    }
}
