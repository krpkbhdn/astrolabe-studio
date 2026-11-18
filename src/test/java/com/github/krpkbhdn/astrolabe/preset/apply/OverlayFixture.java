package com.github.krpkbhdn.astrolabe.preset.apply;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlayService;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** A {@link ProjectOverlaySettings} for tests: one registered project whose overlay lives in a folder of the test's choosing. */
public final class OverlayFixture {

    private OverlayFixture() {}

    public static ProjectOverlaySettings settings(Path projectDir, Path overlayRoot) {
        var overlays = mock(ProjectOverlayService.class);
        when(overlays.overlayOf(1L)).thenReturn(new ProjectOverlay(overlayRoot));
        var projects = mock(ProjectService.class);
        when(projects.findByDirectory(projectDir)).thenReturn(java.util.Optional.of(new Project(1, "p", projectDir, Instant.EPOCH)));
        return new ProjectOverlaySettings(new com.github.krpkbhdn.astrolabe.extension.ProjectOverlays(projects, overlays, mock(com.github.krpkbhdn.astrolabe.git.TaskWorktreeService.class)));
    }
}
