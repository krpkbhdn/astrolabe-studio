package com.github.krpkbhdn.ccmanager.extension;

import com.github.krpkbhdn.ccmanager.git.TaskWorktreeService;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlay;
import com.github.krpkbhdn.ccmanager.overlay.ProjectOverlayService;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The one lookup of an overlay by folder: the registered project or group at the folder, or the parent project of a task worktree folder; any other folder has none. */
@Component
public class ProjectOverlays {

    private final ProjectService projects;
    private final ProjectOverlayService overlays;
    private final TaskWorktreeService taskWorktrees;

    public ProjectOverlays(ProjectService projects, ProjectOverlayService overlays, TaskWorktreeService taskWorktrees) {
        this.projects = projects;
        this.overlays = overlays;
        this.taskWorktrees = taskWorktrees;
    }

    public Optional<ProjectOverlay> of(Path directory) {
        if (directory == null) {
            return Optional.empty();
        }
        return projects.findByDirectory(directory).map(project -> project.id())
                .or(() -> taskWorktrees.find(directory).map(worktree -> worktree.projectId()))
                .map(overlays::overlayOf);
    }

    /** @throws ExtensionException when the folder belongs to no registered project */
    public ProjectOverlay require(Path directory) {
        return of(directory).orElseThrow(() -> new ExtensionException("No project is registered for this folder: " + directory.getFileName()));
    }
}
