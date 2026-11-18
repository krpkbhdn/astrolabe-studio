package com.github.krpkbhdn.astrolabe.claude.proposal;

import com.github.krpkbhdn.astrolabe.claude.hook.DecisionHolder;
import com.github.krpkbhdn.astrolabe.claude.event.HookEvent;
import com.github.krpkbhdn.astrolabe.claude.hook.HookProperties;
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Proposals whose hook call is held open, so the app can answer instead of the terminal dialog. A held call ends with the answer's
 * hook output, or with an empty body (dismissed, settled in the terminal, interrupted, timed out) that leaves the dialog to the terminal.
 * Thread-safe.
 */
@Component
public class ProposalService implements DecisionHolder {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final ChangeListeners listeners = new ChangeListeners("Proposal");
    private final SessionRegistry sessions;
    private final HookProperties properties;
    private final SessionTrust trust;

    public ProposalService(SessionRegistry sessions, HookProperties properties, SessionTrust trust) {
        this.sessions = sessions;
        this.properties = properties;
        this.trust = trust;
        sessions.subscribe(this::releaseOfEndedOrInterruptedSessions);
        sessions.holdActionWhile(this::holdsProposalNotSettledBy);
    }

    /** A parallel tool's event must not clear the session's red while another tool's permission dialog is still open. */
    private boolean holdsProposalNotSettledBy(String astrolabeSessionId, HookEvent event) {
        return held.values().stream().filter(pending -> !pending.forApp())
                .anyMatch(pending -> pending.proposal().astrolabeSessionId().equals(astrolabeSessionId) && !pending.proposal().settledBy(event));
    }

    /**
     * Registers the proposal behind a PermissionRequest or Elicitation event, in event order: [publish] runs just before, except for a request the session's trust level allows.
     * @return completes with the hook output to send back, or empty for an empty 200; already empty for any other event
     */
    @Override
    public CompletableFuture<Optional<String>> hold(HookEvent event, Runnable publish) {
        var proposal = ProposalParser.parse(event);
        var allowed = proposal.filter(PermissionProposal.class::isInstance).map(PermissionProposal.class::cast)
                .filter(permission -> TrustPolicy.autoAllows(trust.of(event.astrolabeSessionId()), permission));
        if (allowed.isPresent()) {
            log.debug("Allowed {} for session {} by its trust level", allowed.get().toolName(), event.astrolabeSessionId());
            return CompletableFuture.completedFuture(Optional.of(ProposalAnswer.PermissionAllow.once().hookOutput(allowed.get())));
        }
        publish.run();
        if (proposal.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return holdProposal(proposal.get(), false)
                .map(held -> held.thenApply(answer -> answer.map(given -> given.hookOutput(proposal.get()))))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

    /**
     * Holds a proposal that has no hook call behind it (a tool of the app MCP server that needs the user's confirmation).
     * @return completes with the user's answer, or with the reason there is none (timed out, over the cap, dismissed or session gone)
     */
    public CompletableFuture<AppDecision> holdForApp(PermissionProposal proposal) {
        var started = System.nanoTime();
        return holdProposal(proposal, true)
                .map(held -> held.thenApply(answer -> answer.<AppDecision>map(AppDecision.Answered::new)
                        .orElseGet(() -> System.nanoTime() - started >= properties.decisionHold().toNanos()
                                ? AppDecision.Unanswered.TIMED_OUT : AppDecision.Unanswered.RELEASED)))
                .orElseGet(() -> CompletableFuture.completedFuture(AppDecision.Unanswered.TOO_MANY_PENDING));
    }

    /** Empty when the cap of held proposals is reached. */
    private Optional<CompletableFuture<Optional<ProposalAnswer>>> holdProposal(Proposal proposal, boolean forApp) {
        var decision = new CompletableFuture<Optional<ProposalAnswer>>()
                .completeOnTimeout(Optional.empty(), properties.decisionHold().toMillis(), TimeUnit.MILLISECONDS);
        var entry = new Held(proposal, decision, forApp);
        synchronized (held) {
            if (held.size() >= properties.maxHeldProposals()) {
                decision.cancel(false);
                return Optional.empty();
            }
            held.put(proposal.id(), entry);
        }
        decision.whenComplete((answer, error) -> {
            synchronized (held) {
                held.remove(proposal.id(), entry);
            }
            listeners.fire();
        });
        listeners.fire();
        releaseOfEndedOrInterruptedSessions();
        return Optional.of(decision);
    }

    /** True for a proposal held with {@link #holdForApp}: no terminal dialog exists for it, so dismissing denies it. */
    public boolean isHeldForApp(String proposalId) {
        var pending = held.get(proposalId);
        return pending != null && pending.forApp();
    }

    public List<Proposal> pending() {
        return held.values().stream().map(Held::proposal).sorted(Comparator.comparing(Proposal::createdAt)).toList();
    }

    /** Hands every held dialog back to the terminal, for shutdown. */
    @Override
    public void releaseAll() {
        held.values().forEach(pending -> pending.decision().complete(Optional.empty()));
    }

    /**
     * @return false when the proposal is gone already (settled elsewhere or timed out)
     * @throws IllegalArgumentException when the answer is of another kind than the proposal
     */
    public boolean answer(String proposalId, ProposalAnswer answer) {
        var pending = held.get(proposalId);
        if (pending == null) {
            return false;
        }
        if (!answer.fits(pending.proposal())) {
            throw new IllegalArgumentException("Answer does not fit proposal " + proposalId);
        }
        return pending.decision().complete(Optional.of(answer));
    }

    /** Hides the proposal from the app; the held call gets an empty body, so the terminal dialog keeps working. */
    public boolean dismiss(String proposalId) {
        var pending = held.get(proposalId);
        return pending != null && pending.decision().complete(Optional.empty());
    }

    /** @return unsubscribe handle; callback fires (any thread) after a proposal appears or goes */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    @EventListener
    public void on(HookEvent event) {
        held.values().stream()
                .filter(pending -> pending.proposal().astrolabeSessionId().equals(event.astrolabeSessionId()) && pending.proposal().settledBy(event))
                .forEach(pending -> pending.decision().complete(Optional.empty()));
    }

    /** Esc and process exit emit no hook event; an idle or gone session cannot be waiting for a hook decision any more (app holds have no hook call, the hold time bounds them). */
    private void releaseOfEndedOrInterruptedSessions() {
        held.values().stream()
                .filter(pending -> !pending.forApp())
                .filter(pending -> sessions.find(pending.proposal().astrolabeSessionId()).map(session -> session.status() == AgentStatus.IDLE).orElse(true))
                .forEach(pending -> pending.decision().complete(Optional.empty()));
    }

    private record Held(Proposal proposal, CompletableFuture<Optional<ProposalAnswer>> decision, boolean forApp) {}
}
