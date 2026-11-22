package com.github.krpkbhdn.astrolabe.preset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** All SQL of the project presets; transactions stay with {@link ProjectPresetService}. */
class ProjectPresetRepository {

    private static final Logger log = LoggerFactory.getLogger(ProjectPresetRepository.class);
    private static final String SELECT = "SELECT id, name, description, bundled_key, bundled_version, edited, revision FROM project_preset WHERE deleted = 0";
    private static final String TOUCH = "UPDATE project_preset SET revision = revision + 1, edited = CASE WHEN bundled_key IS NULL THEN 0 ELSE 1 END WHERE id = ?";

    private final JdbcClient jdbc;
    private final BundledPresetLoader bundled;

    ProjectPresetRepository(JdbcClient jdbc, BundledPresetLoader bundled) {
        this.jdbc = jdbc;
        this.bundled = bundled;
    }

    List<ProjectPreset> list() {
        return jdbc.sql(SELECT).query(this::mapRow).list();
    }

    Optional<ProjectPreset> find(long id) {
        return jdbc.sql(SELECT + " AND id = ?").param(id).query(this::mapRow).optional();
    }

    void rename(long id, String name, String description) {
        jdbc.sql("UPDATE project_preset SET name = ?, description = ?, edited = CASE WHEN bundled_key IS NULL THEN 0 ELSE 1 END WHERE id = ?")
                .param(name).param(description).param(id).update();
    }

    int markBundledDeleted(long id) {
        return jdbc.sql("UPDATE project_preset SET deleted = 1 WHERE id = ? AND bundled_key IS NOT NULL AND deleted = 0").param(id).update();
    }

    int deleteUser(long id) {
        return jdbc.sql("DELETE FROM project_preset WHERE id = ? AND bundled_key IS NULL").param(id).update();
    }

    void touch(long presetId) {
        jdbc.sql(TOUCH).param(presetId).update();
    }

    void updateComponent(long componentId, PresetComponentSpec spec) {
        jdbc.sql("UPDATE project_preset_component SET name = ?, payload = ? WHERE id = ?").param(spec.name()).param(spec.payload().toString()).param(componentId).update();
    }

    void deleteComponent(long componentId) {
        jdbc.sql("DELETE FROM project_preset_component WHERE id = ?").param(componentId).update();
    }

    /** @return id and position of the components of the same kind as the given one, in list order */
    List<long[]> siblings(long presetId, long componentId) {
        return jdbc.sql("SELECT id, position FROM project_preset_component WHERE preset_id = ? "
                        + "AND kind = (SELECT kind FROM project_preset_component WHERE id = ?) ORDER BY position, id")
                .param(presetId).param(componentId).query((rs, n) -> new long[] {rs.getLong("id"), rs.getLong("position")}).list();
    }

    void setPosition(long componentId, long position) {
        jdbc.sql("UPDATE project_preset_component SET position = ? WHERE id = ?").param(position).param(componentId).update();
    }

    record BundledState(int version, boolean edited, boolean deleted) {
    }

    Optional<BundledState> bundledState(String key) {
        return jdbc.sql("SELECT bundled_version, edited, deleted FROM project_preset WHERE bundled_key = ?").param(key)
                .query((rs, n) -> new BundledState(rs.getInt("bundled_version"), rs.getInt("edited") != 0, rs.getInt("deleted") != 0)).optional();
    }

    Optional<Long> bundledId(String key) {
        return jdbc.sql("SELECT id FROM project_preset WHERE bundled_key = ?").param(key).query(Long.class).optional();
    }

    void resetBundled(long id, BundledPreset preset) {
        jdbc.sql("UPDATE project_preset SET name = ?, description = ?, bundled_version = ?, edited = 0, deleted = 0, revision = revision + 1 WHERE id = ?")
                .param(preset.name()).param(preset.description()).param(preset.version()).param(id).update();
        jdbc.sql("DELETE FROM project_preset_component WHERE preset_id = ?").param(id).update();
        preset.components().forEach(spec -> insertComponent(id, PresetComponentValidator.validate(spec.kind(), spec.name(), spec.payload())));
    }

    long insert(String name, String description, String key, Integer version, List<PresetComponentSpec> components) {
        var id = jdbc.sql("INSERT INTO project_preset (name, description, bundled_key, bundled_version) VALUES (?, ?, ?, ?) RETURNING id")
                .param(name).param(description).param(key).param(version).query(Long.class).single();
        components.forEach(spec -> insertComponent(id, PresetComponentValidator.validate(spec.kind(), spec.name(), spec.payload())));
        return id;
    }

    long insertComponent(long presetId, PresetComponentSpec spec) {
        return jdbc.sql("INSERT INTO project_preset_component (preset_id, position, kind, name, payload) "
                        + "VALUES (?, (SELECT COALESCE(MAX(position) + 1, 0) FROM project_preset_component WHERE preset_id = ?), ?, ?, ?) RETURNING id")
                .param(presetId).param(presetId).param(spec.kind().name()).param(spec.name()).param(spec.payload().toString()).query(Long.class).single();
    }

    boolean componentNameTaken(long presetId, PresetComponentSpec spec, long ownId) {
        return jdbc.sql("SELECT COUNT(*) FROM project_preset_component WHERE preset_id = ? AND kind = ? AND name = ? AND id <> ?")
                .param(presetId).param(spec.kind().name()).param(spec.name()).param(ownId).query(Long.class).single() > 0;
    }

    boolean hasOtherSettings(long presetId, long ownId) {
        return jdbc.sql("SELECT COUNT(*) FROM project_preset_component WHERE preset_id = ? AND kind = 'SETTINGS' AND id <> ?").param(presetId).param(ownId).query(Long.class).single() > 0;
    }

    Optional<PresetComponent> findComponent(long componentId) {
        return jdbc.sql("SELECT id, kind, name, payload FROM project_preset_component WHERE id = ?").param(componentId).query(this::mapComponent).list().stream()
                .filter(Objects::nonNull).findFirst();
    }

    Optional<Long> presetOf(long componentId) {
        return jdbc.sql("SELECT preset_id FROM project_preset_component WHERE id = ?").param(componentId).query(Long.class).optional();
    }

    /** A deleted bundled preset does not hold its name (the column is unique, so it is renamed aside; restore puts the shipped name back). */
    boolean nameTaken(String name, long ownId) {
        jdbc.sql("UPDATE project_preset SET name = name || ' [deleted ' || bundled_key || ']' WHERE name = ? AND deleted = 1 AND id <> ?").param(name).param(ownId).update();
        return jdbc.sql("SELECT COUNT(*) FROM project_preset WHERE name = ? AND id <> ?").param(name).param(ownId).query(Long.class).single() > 0;
    }

    /** @return null for a row whose kind or payload cannot be read, so one corrupt component does not hide the rest */
    private PresetComponent mapComponent(ResultSet rs, int row) throws SQLException {
        var id = rs.getLong("id");
        try {
            JsonNode payload = PresetComponentValidator.MAPPER.readTree(rs.getString("payload"));
            return new PresetComponent(id, PresetComponentKind.valueOf(rs.getString("kind")), rs.getString("name"), payload);
        } catch (RuntimeException e) {
            log.warn("Preset component {} skipped, it cannot be read: {}", id, e.getClass().getSimpleName());
            return null;
        }
    }

    private ProjectPreset mapRow(ResultSet rs, int row) throws SQLException {
        var id = rs.getLong("id");
        var key = rs.getString("bundled_key");
        var edited = rs.getInt("edited") != 0;
        var storedVersion = rs.getInt("bundled_version");
        var newer = key != null && bundled.presets().stream().anyMatch(p -> p.key().equals(key) && p.version() > storedVersion);
        var components = new ArrayList<>(jdbc.sql("SELECT id, kind, name, payload FROM project_preset_component WHERE preset_id = ? ORDER BY position, id")
                .param(id).query(this::mapComponent).list());
        components.removeIf(Objects::isNull);
        return new ProjectPreset(id, rs.getString("name"), rs.getString("description"), key, edited, edited && newer, rs.getInt("revision"), List.copyOf(components));
    }
}
