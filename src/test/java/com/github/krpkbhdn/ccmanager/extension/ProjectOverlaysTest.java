package com.github.krpkbhdn.ccmanager.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.git.TaskWorktree;
import com.github.krpkbhdn.ccmanager.git.TaskWorktreeService;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlay;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlayService;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectOverlaysTest {

    private static final Path PROJECT = Path.of("project").toAbsolutePath();
    private static final Path WORKTREE = Path.of("worktree").toAbsolutePath();

    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectOverlayService service = mock(ProjectOverlayService.class);
    private final TaskWorktreeService worktrees = mock(TaskWorktreeService.class);
    private final ProjectOverlays overlays = new ProjectOverlays(projects, service, worktrees);

    @Test
    void aRegisteredFolderAndTheTaskWorktreeOfItsProjectShareOneOverlay() {
        var overlay = new ProjectOverlay(Path.of("overlay"));
        when(projects.findByDirectory(PROJECT)).thenReturn(Optional.of(new Project(7, "p", PROJECT, Instant.EPOCH)));
        when(service.overlayOf(7)).thenReturn(overlay);
        when(worktrees.find(WORKTREE)).thenReturn(Optional.of(new TaskWorktree(1, 7, WORKTREE, "task/x", "main", "x")));

        assertThat(overlays.of(PROJECT)).contains(overlay);
        assertThat(overlays.of(WORKTREE)).contains(overlay);
    }

    @Test
    void anyOtherFolderHasNoOverlayAndRequireSaysSo() {
        assertThat(overlays.of(Path.of("elsewhere"))).isEmpty();
        assertThat(overlays.of(null)).isEmpty();
        assertThatThrownBy(() -> overlays.require(Path.of("elsewhere"))).isInstanceOf(ExtensionException.class);
    }
}
