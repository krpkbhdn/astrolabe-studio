package com.github.krpkbhdn.astrolabe.app.mcp;

import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher;
import com.github.krpkbhdn.astrolabe.claude.mcp.AssistantMcpServer;
import com.github.krpkbhdn.astrolabe.claude.mcp.McpJsonRpcServer;
import com.github.krpkbhdn.astrolabe.claude.proposal.AppDecision;
import com.github.krpkbhdn.astrolabe.claude.proposal.PermissionProposal;
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalAnswer.PermissionAllow;
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalService;
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The separate MCP server {@code astrolabe-app} of the Assistant session; it knows only {@link AssistantMcpTool}s and never runs project tools.
 * The URL secret alone reaches only auto-approved tools; every other tool runs only after the user allows it in the app, not in Claude.
 */
@Component
public class AssistantMcpEndpoint extends McpJsonRpcServer<AssistantMcpTool> implements AssistantMcpServer {

    static final String INSTRUCTIONS = "You control Astrolabe Studio through these tools. "
            + "Resolve project names with list_projects. Act directly instead of asking the user to do it. "
            + "Tools that change things ask the user in astrolabe and may be refused.";
    private static final String MAIN_AGENT = "";
    static final String NO_ASSISTANT = "No live Assistant session";
    static final String DENIED = "Denied by the user in astrolabe";
    static final String TIMED_OUT = "The user did not answer in astrolabe in time";
    static final String TOO_MANY_PENDING = "Too many approvals are waiting in astrolabe; try again later";
    static final String RELEASED = "The approval was dismissed or the Assistant session ended before the user answered";

    private final List<String> toolNames;
    private final List<String> autoApprovedToolNames;
    private final ProposalService proposals;
    private final SessionRegistry sessions;

    public AssistantMcpEndpoint(List<AssistantMcpTool> tools, JsonMapper mapper, ProposalService proposals, SessionRegistry sessions) {
        super(tools, mapper, SERVER_NAME, INSTRUCTIONS);
        this.toolNames = tools.stream().map(AssistantMcpTool::name).sorted().toList();
        this.autoApprovedToolNames = tools.stream().filter(AssistantMcpTool::autoApproved).map(AssistantMcpTool::name).sorted().toList();
        this.proposals = proposals;
        this.sessions = sessions;
    }

    @Override
    public List<String> toolNames() {
        return toolNames;
    }

    public List<String> autoApprovedToolNames() {
        return autoApprovedToolNames;
    }

    @Override
    public Optional<String> handle(byte[] body) {
        return respond(body, this::callConfirmed);
    }

    private String callConfirmed(AssistantMcpTool tool, JsonNode arguments) {
        if (!tool.autoApproved()) {
            requireUserAllow(tool, arguments);
        }
        return tool.call(arguments);
    }

    private void requireUserAllow(AssistantMcpTool tool, JsonNode arguments) {
        var assistant = sessions.sessions(ClaudeLauncher.ASSISTANT_PROJECT_ID).stream()
                .filter(session -> session.status() != AgentStatus.OFFLINE).findFirst()
                .map(SessionSnapshot::astrolabeSessionId).orElseThrow(() -> new IllegalStateException(NO_ASSISTANT));
        var proposal = new PermissionProposal(UUID.randomUUID().toString(), assistant, Instant.now(), MAIN_AGENT, TOOL_NAME_PREFIX + tool.name(), tool.approvalInput(arguments), arguments, List.of(), PermissionProposal.Origin.ASSISTANT);
        var message = switch (proposals.holdForApp(proposal).join()) {
            case AppDecision.Answered answered when answered.answer() instanceof PermissionAllow -> null;
            case AppDecision.Answered ignored -> DENIED;
            case AppDecision.Unanswered.TIMED_OUT -> TIMED_OUT;
            case AppDecision.Unanswered.TOO_MANY_PENDING -> TOO_MANY_PENDING;
            case AppDecision.Unanswered.RELEASED -> RELEASED;
        };
        if (message != null) {
            throw new IllegalStateException(message);
        }
    }
}
