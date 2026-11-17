package com.github.krpkbhdn.astrolabe.claude.session;

import com.github.krpkbhdn.astrolabe.claude.event.HookEvent;
import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Live sessions and their status, derived from hook events and process lifecycle. Thread-safe. */
@Component
@EnableConfigurationProperties(SessionProperties.class)
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Session");
    private final SessionProperties properties;

    private final TranscriptService transcripts;
    private final Clock clock;
    private volatile BiPredicate<String, HookEvent> heldProposal = (astrolabeSessionId, event) -> false;

    @Autowired
    public SessionRegistry(SessionProperties properties, TranscriptService transcripts) {
        this(properties, transcripts, Clock.systemUTC());
    }

    public SessionRegistry(SessionProperties properties, TranscriptService transcripts, Clock clock) {
        this.properties = properties;
        this.transcripts = transcripts;
        this.clock = clock;
    }

    /** The proposal holder says whether a session still waits for the user on a proposal that this event does not settle (set by it, as it depends on this registry). */
    public void holdActionWhile(BiPredicate<String, HookEvent> proposalHeld) {
        this.heldProposal = proposalHeld;
    }

    /** Creates a live session in IDLE state and returns its astrolabe id. */
    public String register(long projectId) {
        var astrolabeSessionId = UUID.randomUUID().toString();
        states.put(astrolabeSessionId, SessionState.initial(astrolabeSessionId, projectId, clock.instant()));
        listeners.fire();
        return astrolabeSessionId;
    }

    /** Process exited: the session leaves the registry. */
    public void markEnded(String astrolabeSessionId, int exitCode) {
        log.debug("Session {} ended with exit code {}", astrolabeSessionId, exitCode);
        discard(astrolabeSessionId);
    }

    /** The user closed the session: it leaves at once so late hooks of the exiting process raise no status or notification. */
    public void discard(String astrolabeSessionId) {
        if (states.remove(astrolabeSessionId) != null) {
            listeners.fire();
        }
    }

    /** User pressed Esc in the terminal: Claude fires no Stop on interrupt, so WORKING becomes IDLE here. */
    public void markInterrupted(String astrolabeSessionId) {
        update(astrolabeSessionId, StatusReducer::interrupted);
    }

    /** Events of sessions this app did not register are ignored. */
    @EventListener
    public void on(HookEvent event) {
        var topic = topicAfter(event);
        update(event.astrolabeSessionId(), state -> {
            var next = StatusReducer.reduce(state, event, heldProposal.test(event.astrolabeSessionId(), event));
            return topic.map(next::withTopic).orElse(next);
        });
    }

    /** Claude writes the generated title to the transcript around prompts and turn ends; tool events never change it. */
    private Optional<String> topicAfter(HookEvent event) {
        var mayCarryTopic = switch (event.type()) {
            case SESSION_START, USER_PROMPT_SUBMIT, STOP -> !event.fromSubagent() && states.containsKey(event.astrolabeSessionId());
            default -> false;
        };
        return mayCarryTopic ? transcripts.title(event.text("transcript_path")) : Optional.empty();
    }

    public List<SessionSnapshot> sessions() {
        return states.values().stream()
                .map(this::snapshotOf)
                .sorted(Comparator.comparing(SessionSnapshot::startedAt))
                .toList();
    }

    public List<SessionSnapshot> sessions(long projectId) {
        return sessions().stream().filter(session -> session.projectId() == projectId).toList();
    }

    public Optional<SessionSnapshot> find(String astrolabeSessionId) {
        return Optional.ofNullable(states.get(astrolabeSessionId)).map(this::snapshotOf);
    }

    /** Max urgency over the project's live sessions; OFFLINE when there are none. */
    public AgentStatus projectStatus(long projectId) {
        return sessions(projectId).stream()
                .map(SessionSnapshot::status)
                .max(Comparator.naturalOrder())
                .orElse(AgentStatus.OFFLINE);
    }

    /** @return unsubscribe handle; callback fires (any thread) after any session or status change */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    private SessionSnapshot snapshotOf(SessionState state) {
        return state.toSnapshot(clock.instant(), properties.subagentStaleAfter());
    }

    private void update(String astrolabeSessionId, UnaryOperator<SessionState> change) {
        if (states.computeIfPresent(astrolabeSessionId, (id, state) -> change.apply(state)) != null) {
            listeners.fire();
        }
    }
}
