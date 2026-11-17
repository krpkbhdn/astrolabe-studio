package com.github.krpkbhdn.ccmanager.project;

import com.github.krpkbhdn.ccmanager.config.UniqueViolations;
import com.github.krpkbhdn.ccmanager.workspace.ChangeListeners;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** Projects are plain directories registered by the user; stored in SQLite. */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String SELECT = "SELECT id, name, path, created_at, model, effort, is_group, group_id, trust_level FROM project";

    private final JdbcClient jdbc;
    private final GroupWorkspace groupWorkspace;
    private final ChangeListeners listeners = new ChangeListeners("Project");
    private final List<LongConsumer> removalListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ProjectService(JdbcClient jdbc, GroupWorkspace groupWorkspace) {
        this.jdbc = jdbc;
        this.groupWorkspace = groupWorkspace;
    }

    public List<Project> list() {
        return jdbc.sql(SELECT + " ORDER BY name COLLATE NOCASE, id").query(ProjectService::mapRow).list();
    }

    public Optional<Project> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(ProjectService::mapRow).optional();
    }

    /** @throws IllegalArgumentException with a user-readable message (not a directory, already added...) */
    public Project add(Path directory) {
        var path = realDirectory(directory);
        if (findByPath(path).isPresent()) {
            throw alreadyAdded(path);
        }
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO project (name, path, created_at) VALUES (?, ?, ?)")
                    .param(nameOf(path))
                    .param(path.toString())
                    .param(Instant.now().toString())
                    .update(keys);
        } catch (DataAccessException e) {
            throw UniqueViolations.isUniqueViolation(e) ? alreadyAdded(path) : e;
        }
        var project = find(keys.getKey().longValue()).orElseThrow();
        fireChanged();
        return project;
    }

    private static IllegalArgumentException alreadyAdded(Path path) {
        return new IllegalArgumentException("This directory is already added as a project: " + path);
    }

    public Project rename(long id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
        var updated = jdbc.sql("UPDATE project SET name = ? WHERE id = ?").param(name.strip()).param(id).update();
        if (updated == 0) {
            throw new IllegalArgumentException("Project not found: " + id);
        }
        fireChanged();
        return find(id).orElseThrow();
    }

    /** Stores the model and effort new sessions of the project start with. */
    public Project selectModel(long id, ModelSelection selection) {
        var updated = jdbc.sql("UPDATE project SET model = ?, effort = ? WHERE id = ?")
                .param(selection.model()).param(selection.effort()).param(id).update();
        if (updated == 0) {
            throw new IllegalArgumentException("Project not found: " + id);
        }
        fireChanged();
        return find(id).orElseThrow();
    }

    /** Stores the trust level override of the sessions the project starts next; groups always ask, so their level cannot be set. */
    public Project selectTrust(long id, TrustLevel level) {
        return storeTrust(id, level.name());
    }

    /** Clears the override: the project follows the app default trust level again. */
    public Project useDefaultTrust(long id) {
        return storeTrust(id, null);
    }

    private Project storeTrust(long id, String level) {
        var updated = jdbc.sql("UPDATE project SET trust_level = ? WHERE id = ? AND is_group = 0").param(level).param(id).update();
        if (updated == 0) {
            throw new IllegalArgumentException("Project not found or a group: " + id);
        }
        fireChanged();
        return find(id).orElseThrow();
    }

    /** A group's service directory goes with it; its members stay registered. */
    public void remove(long id) {
        var removed = jdbc.sql("DELETE FROM project WHERE id = ? RETURNING path, is_group").param(id)
                .query((rs, row) -> rs.getInt("is_group") == 1 ? rs.getString("path") : null).list();
        if (!removed.isEmpty()) {
            if (removed.get(0) != null) {
                deleteGroupDirectory(Path.of(removed.get(0)));
            }
            removalListeners.forEach(listener -> notifyRemoved(listener, id));
            fireChanged();
        }
    }

    private static void notifyRemoved(LongConsumer listener, long id) {
        try {
            listener.accept(id);
        } catch (RuntimeException e) {
            log.warn("A project removal listener failed for {}", id, e);
        }
    }

    /** [onRemoved] gets the id of every project or group deleted from now on, before the change callbacks fire. */
    public void subscribeRemoval(LongConsumer onRemoved) {
        removalListeners.add(onRemoved);
    }

    private void deleteGroupDirectory(Path directory) {
        try {
            groupWorkspace.delete(directory);
        } catch (RuntimeException e) {
            // the row is gone, so the startup sweep of ProjectGroupService removes the leftover folder
            log.warn("Could not delete the group folder {}", directory, e);
        }
    }

    /** @return unsubscribe handle; callback fires after any add/rename/remove */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    /** The registered project or group whose folder is [directory], matched on the real path the row was stored with. */
    public Optional<Project> findByDirectory(Path directory) {
        try {
            return findByPath(directory.toRealPath());
        } catch (IOException e) {
            return findByPath(directory.toAbsolutePath().normalize());
        }
    }

    private Optional<Project> findByPath(Path path) {
        return jdbc.sql(SELECT + " WHERE path = ?").param(path.toString()).query(ProjectService::mapRow).optional();
    }

    private static Path realDirectory(Path directory) {
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        if (!Files.isReadable(directory)) {
            throw new IllegalArgumentException("Directory is not readable: " + directory);
        }
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nameOf(Path path) {
        var fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    void fireChanged() {
        listeners.fire();
    }

    private static Project mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Project(rs.getLong("id"), rs.getString("name"), Path.of(rs.getString("path")),
                Instant.parse(rs.getString("created_at")), ModelSelection.stored(rs.getString("model"), rs.getString("effort")),
                rs.getInt("is_group") != 0, groupId(rs), TrustLevel.storedOverride(rs.getString("trust_level")));
    }

    private static Long groupId(ResultSet rs) throws SQLException {
        var id = rs.getLong("group_id");
        return rs.wasNull() ? null : id;
    }
}
