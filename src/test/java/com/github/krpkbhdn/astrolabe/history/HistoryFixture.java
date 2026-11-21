package com.github.krpkbhdn.astrolabe.history;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A temporary migrated database, a temporary projects directory and helpers to write transcript lines. */
final class HistoryFixture {

    /** The Flyway migrations run once per JVM; every fixture copies the migrated file. */
    private static final class MigratedTemplate {
        static final Path FILE = create();

        private static Path create() {
            try {
                var file = Files.createTempFile("astrolabe-history-template", ".db");
                file.toFile().deleteOnExit();
                Files.delete(file);
                Flyway.configure().dataSource(new DriverManagerDataSource("jdbc:sqlite:" + file)).load().migrate();
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    final Path projects;
    final JdbcClient jdbc;
    final TranscriptIndexRepository repository;
    final SessionSearchService search;

    HistoryFixture(Path temp) {
        this(temp, true);
    }

    HistoryFixture(Path temp, boolean windowsHost) {
        projects = temp.resolve("projects");
        var db = temp.resolve("test.db");
        try {
            Files.createDirectories(temp);
            Files.copy(MigratedTemplate.FILE, db);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        jdbc = JdbcClient.create(dataSource);
        repository = spy(new TranscriptIndexRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource))));
        var host = mock(HostEnvironment.class);
        when(host.windows()).thenReturn(windowsHost);
        search = new SessionSearchService(jdbc, repository, host, new SessionHistoryProperties(Duration.ofMinutes(1), 200000, 100, 5000, 1048576L, 300, 1000));
    }

    TranscriptIndexer indexer(int maxEntries) {
        return indexer(maxEntries, JsonMapper.shared());
    }

    TranscriptIndexer indexer(int maxEntries, JsonMapper mapper) {
        return new TranscriptIndexer(projects, mapper, repository,
                new SessionHistoryProperties(Duration.ofMinutes(1), maxEntries, 100, 5000, 1048576L, 300, 1000));
    }

    TranscriptIndexer indexerEvictingIn(int maxEntries, int evictChunk) {
        return new TranscriptIndexer(projects, JsonMapper.shared(), repository,
                new SessionHistoryProperties(Duration.ofMinutes(1), maxEntries, 100, evictChunk, 1048576L, 300, 1000));
    }

    TranscriptIndexer indexer(int maxEntries, long batchBytes) {
        return new TranscriptIndexer(projects, JsonMapper.shared(), repository,
                new SessionHistoryProperties(Duration.ofMinutes(1), maxEntries, 100, 5000, batchBytes, 300, 1000));
    }

    TranscriptIndexer indexerWithBatchLines(int maxEntries, int batchLines) {
        return new TranscriptIndexer(projects, JsonMapper.shared(), repository,
                new SessionHistoryProperties(Duration.ofMinutes(1), maxEntries, 100, 5000, 1048576L, batchLines, 1000));
    }

    TranscriptIndexer indexerKeepingToolIds(int maxEntries, int maxSensitiveToolIds) {
        return new TranscriptIndexer(projects, JsonMapper.shared(), repository,
                new SessionHistoryProperties(Duration.ofMinutes(1), maxEntries, 100, 5000, 1048576L, 300, maxSensitiveToolIds));
    }

    static String userLine(String uuid, String cwd, String at, String text) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"s1\",\"cwd\":" + JsonMapper.shared().writeValueAsString(cwd)
                + ",\"timestamp\":\"" + at + "\",\"message\":{\"content\":" + JsonMapper.shared().writeValueAsString(text) + "}}\n";
    }

    static String toolCallLine(String uuid, String cwd, String at, String toolId, String inputJson) {
        return "{\"type\":\"assistant\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"s1\",\"cwd\":" + JsonMapper.shared().writeValueAsString(cwd)
                + ",\"timestamp\":\"" + at + "\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"" + toolId
                + "\",\"name\":\"Read\",\"input\":" + inputJson + "}]}}\n";
    }

    static String toolResultLine(String uuid, String cwd, String at, String toolId, String text) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"s1\",\"cwd\":" + JsonMapper.shared().writeValueAsString(cwd)
                + ",\"timestamp\":\"" + at + "\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"" + toolId
                + "\",\"content\":" + JsonMapper.shared().writeValueAsString(text) + "}]}}\n";
    }

    Path transcript(String project, String name) {
        return projects.resolve(project).resolve(name + ".jsonl");
    }

    void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void append(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
