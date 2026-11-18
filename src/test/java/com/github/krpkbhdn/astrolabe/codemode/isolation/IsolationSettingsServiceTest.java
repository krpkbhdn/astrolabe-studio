package com.github.krpkbhdn.astrolabe.codemode.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IsolationSettingsServiceTest {

    @TempDir
    Path temp;

    @Test
    void defaultsToGraalJsAndStoresTheChosenBackend() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        var service = new IsolationSettingsService(JdbcClient.create(dataSource));
        var fired = new AtomicInteger();
        service.subscribe(fired::incrementAndGet);

        assertThat(service.get()).isEqualTo(IsolationBackend.GRAALJS);
        service.set(IsolationBackend.PODMAN);

        assertThat(service.get()).isEqualTo(IsolationBackend.PODMAN);
        assertThat(fired).hasValue(1);
    }
}
