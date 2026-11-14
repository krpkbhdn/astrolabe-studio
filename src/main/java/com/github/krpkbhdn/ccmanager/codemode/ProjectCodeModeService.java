package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.config.NameRules;
import com.github.krpkbhdn.ccmanager.workspace.ChangeListeners;
import java.util.HashSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Per-project Code Mode setting in SQLite; a project without a row is disabled with no servers. */
@Service
public class ProjectCodeModeService {

    public static final int MAX_SERVERS = 50;
    public static final int MAX_SERVER_NAME = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Map<String, Path> sessionDirectories = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Code mode");

    public ProjectCodeModeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProjectCodeMode get(long projectId) {
        return jdbc.sql("SELECT enabled, servers FROM project_code_mode WHERE project_id = ?").param(projectId)
                .query((rs, row) -> new ProjectCodeMode(projectId, rs.getInt("enabled") != 0,
                        JSON.readValue(rs.getString("servers"), new TypeReference<List<String>>() { })))
                .optional().orElseGet(() -> new ProjectCodeMode(projectId, false, List.of()));
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid server names */
    public ProjectCodeMode save(long projectId, boolean enabled, List<String> servers) {
        var clean = validate(servers);
        jdbc.sql("INSERT INTO project_code_mode (project_id, enabled, servers) VALUES (?, ?, ?) "
                + "ON CONFLICT(project_id) DO UPDATE SET enabled = excluded.enabled, servers = excluded.servers")
                .param(projectId).param(enabled ? 1 : 0).param(JSON.writeValueAsString(clean)).update();
        listeners.fire();
        return get(projectId);
    }

    /** Where a session's Claude runs (the project folder or one of its worktrees), because Code Mode reads that folder's .mcp.json and approvals. */
    public void rememberDirectory(String sessionId, Path directory) {
        sessionDirectories.put(sessionId, directory);
    }

    public Optional<Path> directoryOf(String sessionId) {
        return Optional.ofNullable(sessionDirectories.get(sessionId));
    }

    public void forgetDirectory(String sessionId) {
        sessionDirectories.remove(sessionId);
    }

    public Set<String> rememberedSessionIds() {
        return Set.copyOf(sessionDirectories.keySet());
    }

    /** @return unsubscribe handle; callback fires (any thread) after any change made through this service */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    private static List<String> validate(List<String> servers) {
        if (servers.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("At most " + MAX_SERVERS + " servers are allowed.");
        }
        var clean = servers.stream().map(name -> NameRules.requireName(name, MAX_SERVER_NAME)).toList();
        if (new HashSet<>(clean).size() != clean.size()) {
            throw new IllegalArgumentException("Server names must be unique.");
        }
        return clean;
    }
}
