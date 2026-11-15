package com.github.krpkbhdn.ccmanager.project;

import com.github.krpkbhdn.ccmanager.workspace.ChangeListeners;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The app-wide default trust level of projects that have no override of their own (single row, seeded TRUSTED by V31, ASK since V32; ASK or TRUSTED only). */
@Service
public class TrustSettingsService {

    private final JdbcClient jdbc;
    private final ChangeListeners listeners = new ChangeListeners("Trust settings");

    public TrustSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Fails closed: a missing row is ASK, and a stored AUTONOMOUS (only by a manual edit) is clamped to TRUSTED. */
    public TrustLevel defaultLevel() {
        var level = storedLevel().map(TrustLevel::stored).orElse(TrustLevel.ASK);
        return level == TrustLevel.AUTONOMOUS ? TrustLevel.TRUSTED : level;
    }

    /** @throws IllegalArgumentException for AUTONOMOUS, which is chosen per project only */
    public void setDefaultLevel(TrustLevel level) {
        if (level == TrustLevel.AUTONOMOUS) {
            throw new IllegalArgumentException("Autonomous cannot be the app default; set it per project");
        }
        if (storedLevel().filter(level.name()::equals).isPresent()) {
            return;
        }
        jdbc.sql("INSERT INTO trust_settings (id, default_level) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET default_level = excluded.default_level")
                .param(level.name()).update();
        listeners.fire();
    }

    private Optional<String> storedLevel() {
        return jdbc.sql("SELECT default_level FROM trust_settings WHERE id = 1").query(String.class).optional();
    }

    public TrustLevel effective(Project project) {
        return TrustLevel.effective(project, defaultLevel());
    }

    /** @return unsubscribe handle; callback fires (any thread) after a change */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }
}
