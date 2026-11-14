package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProjectCodeModeServiceTest {

    @TempDir
    Path temp;

    ProjectCodeModeService service;
    JdbcClient jdbc;
    long projectId;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?foreign_keys=on");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        service = new ProjectCodeModeService(jdbc);
        jdbc.sql("INSERT INTO project (name, path, created_at) VALUES ('app', ?, 'now')").param(temp.toString()).update();
        projectId = jdbc.sql("SELECT id FROM project").query(Long.class).single();
    }

    @Test
    void getDefaultsToDisabledWithoutServers() {
        assertThat(service.get(projectId)).isEqualTo(new ProjectCodeMode(projectId, false, List.of()));
    }

    @Test
    void saveStoresAndOverwritesAndNotifies() {
        var fired = new AtomicInteger();
        service.subscribe(fired::incrementAndGet);

        service.save(projectId, true, List.of(" github ", "jira"));
        var saved = service.save(projectId, true, List.of("jira"));

        assertThat(saved).isEqualTo(new ProjectCodeMode(projectId, true, List.of("jira")));
        assertThat(fired).hasValue(2);
    }

    @Test
    void saveRejectsBlankDuplicateAndTooManyServers() {
        assertThatThrownBy(() -> service.save(projectId, true, List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(projectId, true, List.of("a", "a"))).isInstanceOf(IllegalArgumentException.class);
        var many = IntStream.range(0, ProjectCodeModeService.MAX_SERVERS + 1).mapToObj(i -> "s" + i).toList();
        assertThatThrownBy(() -> service.save(projectId, true, many)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingTheProjectRemovesItsSetting() {
        service.save(projectId, true, List.of("a"));
        jdbc.sql("DELETE FROM project WHERE id = ?").param(projectId).update();

        assertThat(service.get(projectId).enabled()).isFalse();
    }
}
