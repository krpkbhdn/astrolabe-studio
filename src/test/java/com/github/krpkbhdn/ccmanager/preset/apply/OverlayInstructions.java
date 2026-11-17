package com.github.krpkbhdn.ccmanager.preset.apply;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlayService;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Appliers over a real overlay folder in {@code dataDir} for one registered project. */
final class OverlayInstructions {

    private OverlayInstructions() {}

    static ProjectOverlaySettings settings(Path dataDir, long projectId, Path projectDir) {
        var projects = mock(ProjectService.class);
        when(projects.findByDirectory(projectDir)).thenReturn(java.util.Optional.of(new Project(projectId, "p", projectDir, Instant.now())));
        var overlays = new ProjectOverlayService(new AppProperties(dataDir), projects, mock(com.github.krpkbhdn.ccmanager.toolchain.GroupLspOverrides.class));
        return new ProjectOverlaySettings(new com.github.krpkbhdn.ccmanager.extension.ProjectOverlays(projects, overlays, mock(com.github.krpkbhdn.ccmanager.git.TaskWorktreeService.class)));
    }

    static InstructionApplier applier(Path dataDir, long projectId, Path projectDir) {
        return new InstructionApplier(settings(dataDir, projectId, projectDir));
    }
}
