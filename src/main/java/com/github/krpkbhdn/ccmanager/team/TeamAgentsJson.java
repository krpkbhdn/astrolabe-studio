package com.github.krpkbhdn.ccmanager.team;

import com.github.krpkbhdn.ccmanager.overlay.OverlayAgent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Turns a team into the claude --agents JSON (one native subagent per non-lead role) and the lead's system prompt addendum. */
public final class TeamAgentsJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TeamAgentsJson() {}

    /** Compact JSON, a single line, so it survives as one command line argument. */
    public static String agents(Team team) {
        return agents(team, List.of());
    }

    /** The roles of [team] (may be null) plus the [overlayAgents] of the project; a role wins over an overlay agent of the same name. */
    public static String agents(Team team, Collection<OverlayAgent> overlayAgents) {
        ObjectNode agents = MAPPER.createObjectNode();
        if (team != null) {
            for (TeamRole role : team.subagents()) {
                ObjectNode agent = agents.putObject(role.name());
                agent.put("description", role.draft().description());
                agent.put("prompt", role.draft().prompt());
                putIfPresent(agent, "model", role.draft().model());
                putIfPresent(agent, "effort", role.draft().effort());
                putTools(agent, role.draft().tools());
            }
        }
        for (OverlayAgent overlay : overlayAgents) {
            if (!agents.has(overlay.name())) {
                ObjectNode agent = agents.putObject(overlay.name());
                agent.put("description", overlay.description());
                agent.put("prompt", overlay.prompt());
                putIfPresent(agent, "model", overlay.model());
                putTools(agent, overlay.tools());
            }
        }
        return MAPPER.writeValueAsString(agents);
    }

    /** Appended to the lead's system prompt: who is on the team and that work goes to them through the Agent tool. */
    public static String leadAddendum(Team team) {
        var roster = team.subagents().stream()
                .map(role -> "- " + role.name() + ": " + role.draft().description())
                .collect(Collectors.joining("\n"));
        var leadPrompt = team.lead().map(lead -> lead.draft().prompt() + "\n\n").orElse("");
        return leadPrompt + "You are the lead of the team \"" + team.name() + "\". Delegate work to these subagents with the Agent tool "
                + "(subagent_type set to the role name) instead of doing it yourself, choosing by the description:\n" + roster
                + "\nGive each subagent a concrete, self-contained step, then check what it reports before you continue.";
    }

    private static void putTools(ObjectNode agent, String tools) {
        if (tools != null) {
            var array = agent.putArray("tools");
            Arrays.stream(tools.split(",")).map(String::strip).filter(tool -> !tool.isEmpty()).forEach(array::add);
        }
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }
}
