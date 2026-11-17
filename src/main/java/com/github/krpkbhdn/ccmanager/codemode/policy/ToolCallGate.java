package com.github.krpkbhdn.ccmanager.codemode.policy;

import com.github.krpkbhdn.ccmanager.claude.proposal.AppDecision;
import com.github.krpkbhdn.ccmanager.claude.proposal.PermissionProposal;
import com.github.krpkbhdn.ccmanager.claude.proposal.PermissionProposal.Suggestion;
import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalAnswer.PermissionAllow;
import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalService;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.RemoteTool;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.codemode.ServerNames;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a script may call a remote MCP tool: read-only tools and granted ones run at once, everything else needs the user's answer in the app,
 * and any failure or abandoned question denies it. A grant belongs to the server's definition, so a changed or replaced server never inherits it.
 * A project setting can allow every call, or those of chosen servers, without asking; deny and ask rules of Claude Code still win over it.
 */
@Component
public class ToolCallGate {

    static final String SESSION_GRANT_TYPE = "codeModeSessionGrant";
    static final String SERVER_GRANT_TYPE = "codeModeServerGrant";
    static final String DENIED = "Denied by the user in cc-manager";
    static final String TIMED_OUT = "The user did not answer in cc-manager in time";
    static final String UNAVAILABLE = "The user could not be asked in cc-manager (no open approval, or too many waiting)";
    static final String DENIED_BY_RULE = "Denied by a deny rule in the Claude Code permission settings";
    static final String FAILED = "The permission check failed, so the call was not allowed";
    private static final Logger log = LoggerFactory.getLogger(ToolCallGate.class);
    private static final int MAX_LABEL = 100;
    private static final String MAIN_AGENT = "";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ProposalService proposals;
    private final Map<String, Set<ToolGrant>> grants = new ConcurrentHashMap<>();
    private final Map<String, Set<ServerGrant>> serverGrants = new ConcurrentHashMap<>();
    private final PermissionRules rules;
    private final ProjectCodeModeService projectModes;

    private record ToolGrant(String server, String tool, McpServerDefinition definition) {}

    private record ServerGrant(String server, McpServerDefinition definition) {}

    @Autowired
    public ToolCallGate(ProposalService proposals, PermissionRules rules, ProjectCodeModeService projectModes) {
        this.proposals = proposals;
        this.rules = rules;
        this.projectModes = projectModes;
    }

    /**
     * Claude Code permission rules come first: deny blocks, ask always asks, allow (from the Claude Code settings, exact tool or server; like Claude Code it
     * does not depend on the destructive hint or a cut preview) runs the call. Otherwise read-only tools run, and a tool granted for the session runs
     * only when its arguments can be shown in full (a whole-server grant does not depend on that).
     */
    public Decision decide(RemoteTool tool, McpServerDefinition definition, JsonNode arguments, SessionContext session) {
        try {
            var verdict = rules.verdict(session, definition.name(), tool.name());
            if (verdict == PermissionRules.Verdict.DENY) {
                return Decision.denied(DENIED_BY_RULE);
            }
            var preview = ArgumentPreview.of(arguments);
            if (verdict == PermissionRules.Verdict.ASK) {
                return askUser(tool, definition, preview, session);
            }
            if (verdict == PermissionRules.Verdict.ALLOW || tool.isSafeToRunUnasked()) {
                return Decision.AUTO;
            }
            if (projectModes.get(session.projectId()).toolCalls().allows(tool.server(), definition.name())) {
                return Decision.PROJECT_SETTING;
            }
            if (isServerGranted(session, tool, definition) || !preview.truncated() && isGranted(session, tool, definition)) {
                return Decision.AUTO;
            }
            return askUser(tool, definition, preview, session);
        } catch (RuntimeException e) {
            log.warn("Code Mode permission check failed: {}", e.getClass().getSimpleName());
            return Decision.denied(FAILED);
        }
    }

    public void allowForSession(SessionContext session, RemoteTool tool, McpServerDefinition definition) {
        grants.computeIfAbsent(session.sessionId(), id -> ConcurrentHashMap.newKeySet()).add(new ToolGrant(tool.server(), tool.name(), definition));
    }

    /** The user's explicit broad choice: covers later calls of every tool of the server, destructive ones included. */
    public void allowServerForSession(SessionContext session, String server, McpServerDefinition definition) {
        serverGrants.computeIfAbsent(session.sessionId(), id -> ConcurrentHashMap.newKeySet()).add(new ServerGrant(server, definition));
    }

    public void forgetSession(String sessionId) {
        grants.remove(sessionId);
        serverGrants.remove(sessionId);
    }

    /** The server behind the alias is gone or changed: none of its grants may outlive it. */
    public void forgetServer(String sessionId, String server) {
        grants.computeIfPresent(sessionId, (id, set) -> {
            set.removeIf(grant -> grant.server().equals(server));
            return set;
        });
        serverGrants.computeIfPresent(sessionId, (id, set) -> {
            set.removeIf(grant -> grant.server().equals(server));
            return set;
        });
    }

    private boolean isServerGranted(SessionContext session, RemoteTool tool, McpServerDefinition definition) {
        return serverGrants.getOrDefault(session.sessionId(), Set.of()).contains(new ServerGrant(tool.server(), definition));
    }

    /** A tool-level grant never covers a tool that declares itself destructive now, even if it did not when it was granted. */
    private boolean isGranted(SessionContext session, RemoteTool tool, McpServerDefinition definition) {
        return !declaresDestructive(tool) && grants.getOrDefault(session.sessionId(), Set.of()).contains(new ToolGrant(tool.server(), tool.name(), definition));
    }

    private static boolean declaresDestructive(RemoteTool tool) {
        return tool.destructiveHintDeclared() && tool.destructiveHint();
    }

    private static boolean hasMarker(PermissionAllow allow, String type) {
        return allow.remembered().stream().anyMatch(raw -> type.equals(raw.path("type").asString()));
    }

    private long toolsAllowedOn(SessionContext session, String server) {
        return grants.getOrDefault(session.sessionId(), Set.of()).stream().filter(grant -> grant.server().equals(server)).count();
    }

    private Decision askUser(RemoteTool tool, McpServerDefinition definition, ArgumentPreview.Preview preview, SessionContext session) {
        var rawLabel = tool.server() + "." + tool.name();
        var label = ServerNames.shown(rawLabel, MAX_LABEL);
        var exact = label.equals(rawLabel);
        var offerToolGrant = exact && !preview.truncated() && !declaresDestructive(tool);
        var suggestions = new ArrayList<Suggestion>();
        if (offerToolGrant) {
            suggestions.add(new Suggestion(MAPPER.createObjectNode().put("type", SESSION_GRANT_TYPE), "Allow " + label + " for this session"));
        }
        if (exact) {
            var already = toolsAllowedOn(session, tool.server());
            suggestions.add(new Suggestion(MAPPER.createObjectNode().put("type", SERVER_GRANT_TYPE), "Allow ALL tools of " + tool.server()
                    + " for this session, including ones marked destructive" + (already > 0 ? " (" + already + " already allowed)" : "")));
        }
        var proposal = new PermissionProposal(UUID.randomUUID().toString(), session.sessionId(), Instant.now(), MAIN_AGENT, label,
                preview.node(), preview.node(), suggestions, PermissionProposal.Origin.CODE_MODE);
        return switch (answerOf(proposal)) {
            case AppDecision.Answered answered when answered.answer() instanceof PermissionAllow allow -> {
                if (offerToolGrant && hasMarker(allow, SESSION_GRANT_TYPE)) {
                    allowForSession(session, tool, definition);
                }
                if (exact && hasMarker(allow, SERVER_GRANT_TYPE)) {
                    allowServerForSession(session, tool.server(), definition);
                }
                yield Decision.USER;
            }
            case AppDecision.Answered ignored -> Decision.denied(DENIED);
            case AppDecision.Unanswered.TIMED_OUT -> Decision.denied(TIMED_OUT);
            case AppDecision.Unanswered.TOO_MANY_PENDING, AppDecision.Unanswered.RELEASED -> Decision.denied(UNAVAILABLE);
        };
    }

    /** The proposal service ends every hold by itself after its decision hold, so there is no second timeout here. */
    private AppDecision answerOf(PermissionProposal proposal) {
        var held = proposals.holdForApp(proposal);
        try {
            return held.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AppDecision.Unanswered.RELEASED;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            if (!held.isDone()) {
                proposals.dismiss(proposal.id());
            }
        }
    }
}
