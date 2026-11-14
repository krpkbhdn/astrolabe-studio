package com.github.krpkbhdn.ccmanager.codemode.isolation;

import com.github.krpkbhdn.ccmanager.workspace.ChangeListeners;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The app-level sandbox backend of Code Mode (single row, seeded GRAALJS). */
@Service
public class IsolationSettingsService {

    private final JdbcClient jdbc;
    private final ChangeListeners listeners = new ChangeListeners("Code mode isolation");

    public IsolationSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public IsolationBackend get() {
        return jdbc.sql("SELECT backend FROM code_mode_isolation WHERE id = 1").query(String.class).optional()
                .map(IsolationBackend::valueOf).orElse(IsolationBackend.GRAALJS);
    }

    public void set(IsolationBackend backend) {
        jdbc.sql("INSERT INTO code_mode_isolation (id, backend) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET backend = excluded.backend")
                .param(backend.name()).update();
        listeners.fire();
    }

    /** @return unsubscribe handle; callback fires (any thread) after a change */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }
}
