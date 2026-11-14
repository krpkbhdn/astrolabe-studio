package com.github.krpkbhdn.ccmanager.codemode.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.TestProperties;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CodeModeAuditTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    @TempDir
    Path temp;

    JdbcClient jdbc;
    Instant clockTime = NOW;
    CodeModeAudit audit;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        var properties = TestProperties.of(Duration.ofSeconds(30), 1000, 1000, 10, Duration.ofMinutes(10), "img", "256m", 1, RETENTION, Duration.ofSeconds(25));
        var clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockTime;
            }
        };
        audit = new CodeModeAudit(jdbc, properties, clock);
    }

    @Test
    void recordStoresOneRowWithoutArgumentsOrResults() {
        jdbc.sql("INSERT INTO project (name, path, created_at) VALUES ('app', ?, 'now')").param(temp.toString()).update();
        audit.record(new SessionContext(7, "s1"), "db", "query", Decision.Kind.ALLOWED_USER, CodeModeAudit.Status.ERROR, 42);

        var row = jdbc.sql("SELECT * FROM code_mode_call").query().singleRow();
        assertThat(row).containsEntry("project_id", 7).containsEntry("session_id", "s1").containsEntry("server", "db")
                .containsEntry("tool", "query").containsEntry("decision", "ALLOWED_USER").containsEntry("status", "ERROR")
                .containsEntry("duration_ms", 42).containsEntry("at", NOW.toEpochMilli());
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "project_id", "session_id", "server", "tool", "decision", "status",
                "duration_ms", "at");
    }

    @Test
    void pruneDeletesOnlyRowsOlderThanRetention() {
        clockTime = NOW.minus(RETENTION).minusSeconds(1);
        audit.record(new SessionContext(1, "s1"), "db", "old", Decision.Kind.DENIED, CodeModeAudit.Status.ERROR, 1);
        clockTime = NOW.minus(RETENTION).plusSeconds(1);
        audit.record(new SessionContext(1, "s1"), "db", "recent", Decision.Kind.ALLOWED_AUTO, CodeModeAudit.Status.OK, 1);
        clockTime = NOW;

        assertThat(audit.prune()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT tool FROM code_mode_call").query(String.class).list()).containsExactly("recent");
    }

    @Test
    void sweepNeverThrows() {
        jdbc.sql("DROP TABLE code_mode_call").update();

        audit.sweep();
    }
}
