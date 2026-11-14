package com.github.krpkbhdn.ccmanager.codemode.policy;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.workspace.ScheduledSweep;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Journal of tool calls made by scripts: who, which tool, decision, outcome and duration, never arguments or results. */
@Component
public class CodeModeAudit extends ScheduledSweep {

    public enum Status { OK, ERROR }

    private static final Logger log = LoggerFactory.getLogger(CodeModeAudit.class);
    private static final Duration PRUNE_INTERVAL = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final CodeModeProperties properties;
    private final Clock clock;

    @Autowired
    public CodeModeAudit(JdbcClient jdbc, CodeModeProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    CodeModeAudit(JdbcClient jdbc, CodeModeProperties properties, Clock clock) {
        super("code-mode-audit", PRUNE_INTERVAL);
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public void record(SessionContext session, String server, String tool, Decision.Kind decision, Status status, long durationMs) {
        jdbc.sql("INSERT INTO code_mode_call (project_id, session_id, server, tool, decision, status, duration_ms, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(session.projectId(), session.sessionId(), server, tool, decision.name(), status.name(), durationMs, clock.millis())
                .update();
    }

    /** @return number of rows deleted */
    public int prune() {
        return jdbc.sql("DELETE FROM code_mode_call WHERE at < ?").param(clock.millis() - properties.auditRetention().toMillis()).update();
    }

    @Override
    public void sweep() {
        try {
            prune();
        } catch (RuntimeException | Error e) {
            log.error("Code Mode audit cleanup failed: {}", e.getClass().getSimpleName());
        }
    }
}
