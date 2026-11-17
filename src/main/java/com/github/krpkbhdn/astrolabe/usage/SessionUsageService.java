package com.github.krpkbhdn.astrolabe.usage;

import com.github.krpkbhdn.astrolabe.claude.hook.StatusLineReport;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps the newest statusLine figures of every live session, for the usage panel and the session header. Thread-safe. */
@Component
public class SessionUsageService {

    private final Map<String, SessionUsage> bySession = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Session usage");
    private final SessionRegistry sessions;

    public SessionUsageService(SessionRegistry sessions) {
        this.sessions = sessions;
        sessions.subscribe(this::dropEndedSessions);
    }

    @EventListener
    public void on(StatusLineReport report) {
        if (sessions.find(report.astrolabeSessionId()).isEmpty()) {
            return;
        }
        var usage = SessionUsageParser.parse(report.astrolabeSessionId(), report.payload(), report.receivedAt());
        bySession.merge(report.astrolabeSessionId(), usage, (current, incoming) -> incoming.receivedAt().isBefore(current.receivedAt()) ? current : incoming);
        if (sessions.find(report.astrolabeSessionId()).isEmpty()) {
            bySession.remove(report.astrolabeSessionId());
            return;
        }
        listeners.fire();
    }

    public Optional<SessionUsage> of(String astrolabeSessionId) {
        return Optional.ofNullable(bySession.get(astrolabeSessionId));
    }

    public Map<String, SessionUsage> all() {
        return Map.copyOf(bySession);
    }

    /** @return unsubscribe handle; callback fires (any thread) after every report */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    private void dropEndedSessions() {
        bySession.keySet().removeIf(id -> sessions.find(id).isEmpty());
    }
}
