package com.github.krpkbhdn.ccmanager.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TrustSettingsServiceTest {

    @TempDir
    Path temp;

    @Test
    void defaultsToTrustedStoresTheChosenLevelAndResolvesProjects() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        var service = new TrustSettingsService(JdbcClient.create(dataSource));
        var fired = new AtomicInteger();
        service.subscribe(fired::incrementAndGet);
        var plain = new Project(1, "p", temp, Instant.now());

        assertThat(service.defaultLevel()).isEqualTo(TrustLevel.TRUSTED);
        assertThat(service.effective(plain)).isEqualTo(TrustLevel.TRUSTED);

        service.setDefaultLevel(TrustLevel.ASK);
        service.setDefaultLevel(TrustLevel.ASK);

        assertThat(service.defaultLevel()).isEqualTo(TrustLevel.ASK);
        assertThat(service.effective(plain)).isEqualTo(TrustLevel.ASK);
        assertThat(service.effective(new Project(2, "q", temp, Instant.now(), ModelSelection.DEFAULT, false, null, TrustLevel.AUTONOMOUS))).isEqualTo(TrustLevel.AUTONOMOUS);
        assertThat(fired).hasValue(1);
    }

    @Test
    void rejectsAutonomousAndClampsAStoredOne() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("clamp.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = JdbcClient.create(dataSource);
        var service = new TrustSettingsService(jdbc);

        assertThatThrownBy(() -> service.setDefaultLevel(TrustLevel.AUTONOMOUS)).isInstanceOf(IllegalArgumentException.class);
        jdbc.sql("UPDATE trust_settings SET default_level = 'AUTONOMOUS'").update();

        assertThat(service.defaultLevel()).isEqualTo(TrustLevel.TRUSTED);
    }

    @Test
    void aMissingRowFailsClosedToAsk() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("missing.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM trust_settings").update();

        assertThat(new TrustSettingsService(jdbc).defaultLevel()).isEqualTo(TrustLevel.ASK);
    }
}
