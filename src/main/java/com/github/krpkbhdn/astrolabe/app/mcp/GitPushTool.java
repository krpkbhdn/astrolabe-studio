package com.github.krpkbhdn.astrolabe.app.mcp;

import com.github.krpkbhdn.astrolabe.git.GitException;
import com.github.krpkbhdn.astrolabe.git.GitMisc.PushOptions;
import com.github.krpkbhdn.astrolabe.git.GitStatusService;
import com.github.krpkbhdn.astrolabe.git.GitSyncService;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class GitPushTool extends GitTool {

    private static final PushOptions PLAIN = new PushOptions(false, false, false);
    private static final PushOptions SET_UPSTREAM = new PushOptions(false, true, false);

    private final GitSyncService sync;

    GitPushTool(ProjectService projects, GitStatusService status, GitSyncService sync, JsonMapper mapper) {
        super(projects, status, mapper);
        this.sync = sync;
    }

    @Override
    public String name() {
        return "git_push";
    }

    @Override
    public String description() {
        return "Pushes the current branch of a project; a branch without upstream is published to its remote. Never forces.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" + PROJECT_PROPERTY + "},\"required\":[\"project\"]}";
    }

    @Override
    String run(Path repo, JsonNode arguments) {
        try {
            sync.push(repo, PLAIN, null);
        } catch (GitException e) {
            if (e.kind() != GitException.Kind.NO_UPSTREAM) {
                throw e;
            }
            sync.push(repo, SET_UPSTREAM, null);
        }
        return "Pushed";
    }
}
