package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.config.NameRules;
import com.github.krpkbhdn.ccmanager.project.ProjectGroupService;
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

/** Per-project Code Mode setting in SQLite; a project without a row is disabled with no servers, a group without a row inherits its members' settings. */
@Service
public class ProjectCodeModeService {

    public static final int MAX_SERVERS = 50;
    public static final int MAX_SERVER_NAME = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Map<String, Path> sessionDirectories = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Code mode");

    private final ProjectGroupService groups;

    public ProjectCodeModeService(JdbcClient jdbc, ProjectGroupService groups) {
        this.jdbc = jdbc;
        this.groups = groups;
    }

    /** What applies to the project: its own row; for a group without a row, the merge over its members. {@link #get} stays the stored row. */
    public ProjectCodeMode effective(long id) {
        if (hasRow(id)) {
            return get(id);
        }
        return GroupCodeModeMerge.of(id, groups.members(id).stream().map(member -> get(member.id())).toList());
    }

    public boolean inherits(long groupId) {
        return !hasRow(groupId);
    }

    /** Saves the inherited values as the group's own, so they can be changed independently of the members. */
    public ProjectCodeMode override(long groupId) {
        if (hasRow(groupId)) {
            return get(groupId);
        }
        var inherited = effective(groupId);
        return save(groupId, inherited.enabled(), inherited.servers(), inherited.toolCalls());
    }

    public void inherit(long groupId) {
        jdbc.sql("DELETE FROM project_code_mode WHERE project_id = ?").param(groupId).update();
        listeners.fire();
    }

    private boolean hasRow(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM project_code_mode WHERE project_id = ?").param(id).query(Integer.class).single() > 0;
    }

    public ProjectCodeMode get(long projectId) {
        return jdbc.sql("SELECT enabled, servers, tool_call_mode, allowed_servers FROM project_code_mode WHERE project_id = ?").param(projectId)
                .query((rs, row) -> new ProjectCodeMode(projectId, rs.getInt("enabled") != 0,
                        JSON.readValue(rs.getString("servers"), new TypeReference<List<String>>() { }), accessOf(rs.getString("tool_call_mode"), rs.getString("allowed_servers"))))
                .optional().orElseGet(() -> new ProjectCodeMode(projectId, false, List.of(), ToolCallAccess.ASK));
    }

    /** Saves enabled and servers and keeps the stored tool call access. */
    public ProjectCodeMode save(long projectId, boolean enabled, List<String> servers) {
        return save(projectId, enabled, servers, get(projectId).toolCalls());
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid server names */
    public ProjectCodeMode save(long projectId, boolean enabled, List<String> servers, ToolCallAccess toolCalls) {
        var clean = validate(servers);
        jdbc.sql("INSERT INTO project_code_mode (project_id, enabled, servers, tool_call_mode, allowed_servers) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(project_id) DO UPDATE SET enabled = excluded.enabled, servers = excluded.servers, "
                + "tool_call_mode = excluded.tool_call_mode, allowed_servers = excluded.allowed_servers")
                .param(projectId).param(enabled ? 1 : 0).param(JSON.writeValueAsString(clean)).param(toolCalls.mode().name())
                .param(JSON.writeValueAsString(validate(toolCalls.servers()))).update();
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

    private static ToolCallAccess accessOf(String mode, String servers) {
        return new ToolCallAccess(ToolCallAccess.Mode.valueOf(mode), JSON.readValue(servers, new TypeReference<List<String>>() { }));
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
