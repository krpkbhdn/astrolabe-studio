package com.github.krpkbhdn.astrolabe.app.mcp;

import com.github.krpkbhdn.astrolabe.git.GitStatusService;
import com.github.krpkbhdn.astrolabe.git.GitWorktreeService;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class GitRemoveWorktreeTool extends GitTool {

    private final GitWorktreeService worktrees;

    GitRemoveWorktreeTool(ProjectService projects, GitStatusService status, GitWorktreeService worktrees, JsonMapper mapper) {
        super(projects, status, mapper);
        this.worktrees = worktrees;
    }

    @Override
    public String name() {
        return "git_remove_worktree";
    }

    @Override
    public String description() {
        return "Removes a linked git worktree folder (never its branch, never the main worktree). Fails when the worktree has uncommitted changes.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" + PROJECT_PROPERTY + ","
                + "\"path\":{\"type\":\"string\",\"description\":\"Folder of the worktree, as listed by git_worktrees\"}},"
                + "\"required\":[\"project\",\"path\"]}";
    }

    @Override
    String run(Path repo, JsonNode arguments) {
        var path = Path.of(requiredText(arguments, "path"));
        worktrees.remove(repo, path, false);
        return "Removed worktree " + path;
    }
}
