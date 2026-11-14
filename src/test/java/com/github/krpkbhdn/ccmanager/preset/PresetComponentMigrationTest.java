package com.github.krpkbhdn.ccmanager.preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PresetComponentMigrationTest {

    @TempDir
    Path temp;

    @Test
    void rebuildKeepsRowsIdsAndConstraintsAndAllowsCodeMode() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        var before = Flyway.configure().dataSource(dataSource).target("29").load();
        before.migrate();
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO project_preset (id, name, description) VALUES (5, 'p', 'd')").update();
        jdbc.sql("INSERT INTO project_preset_component (id, preset_id, position, kind, name, payload) VALUES (7, 5, 1, 'PLUGIN', 'a', '{}'), (9, 5, 2, 'SKILL', 'b', '{\"x\":1}')")
                .update();

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.sql("SELECT id || ':' || kind || ':' || name || ':' || payload FROM project_preset_component ORDER BY id").query(String.class).list())
                .containsExactly("7:PLUGIN:a:{}", "9:SKILL:b:{\"x\":1}");
        jdbc.sql("INSERT INTO project_preset_component (preset_id, position, kind, name, payload) VALUES (5, 3, 'CODE_MODE', 'c', '{}')").update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO project_preset_component (preset_id, position, kind, name, payload) VALUES (5, 4, 'PLUGIN', 'a', '{}')").update())
                .isInstanceOf(RuntimeException.class);
        jdbc.sql("DELETE FROM project_preset WHERE id = 5").update();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM project_preset_component").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sqlite_master WHERE tbl_name = 'project_preset_component' AND type = 'index'").query(Long.class).single())
                .isEqualTo(1);
    }
}
