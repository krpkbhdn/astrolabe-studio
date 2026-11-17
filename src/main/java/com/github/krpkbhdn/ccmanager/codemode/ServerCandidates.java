package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers;
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Which of a project's Code Mode servers a session may use: those found in the user, local, app (overlay) or approved project configuration of its working directory, or provided by an enabled plugin.
 * A plugin server is saved as {@code plugin_<plugin>_<server>} and scripts see its plain key; a name that more than one server resolves to makes all of them ineligible.
 */
@Component
public class ServerCandidates {

    static final String NOT_FOUND = "not found in the Claude configuration or in an enabled plugin";
    static final String NOT_APPROVED = "declared in .mcp.json but not approved in Claude Code";
    static final String AMBIGUOUS = "ambiguous name: more than one server resolves to it";
    static final String NO_PROJECT = "the project is not registered";
    private static final List<ExtensionScope> PRECEDENCE = List.of(ExtensionScope.USER, ExtensionScope.APP, ExtensionScope.PROJECT, ExtensionScope.LOCAL);

    public record Candidate(String name, String stored, String transport, McpServerDefinition definition, String problem) {

        public String scope() {
            return definition == null ? "unknown" : definition.scope().cliValue();
        }

        static Candidate eligible(String alias, String stored, McpServerDefinition definition) {
            return new Candidate(alias, stored, definition.transport().name().toLowerCase(), definition, null);
        }

        static Candidate ineligible(String name, String problem) {
            return new Candidate(name, name, "unknown", null, problem);
        }
    }

    private final ProjectService projects;
    private final ProjectCodeModeService modes;
    private final McpConfigReader reader;
    private final McpService mcp;
    private final PluginMcpServers plugins;

    public ServerCandidates(ProjectService projects, ProjectCodeModeService modes, McpConfigReader reader, McpService mcp, PluginMcpServers plugins) {
        this.projects = projects;
        this.modes = modes;
        this.reader = reader;
        this.mcp = mcp;
        this.plugins = plugins;
    }

    /** One candidate per server the project lists, in the project's order. */
    public List<Candidate> of(SessionContext session) {
        return of(session, Map.of());
    }

    /** @param pinned the alias each saved name got earlier in the session; a plugin server keeps it even when the configured servers change */
    public List<Candidate> of(SessionContext session, Map<String, String> pinned) {
        var configured = modes.effective(session.projectId()).servers();
        var project = projects.find(session.projectId());
        if (project.isEmpty()) {
            return configured.stream().map(name -> Candidate.ineligible(name, NO_PROJECT)).toList();
        }
        var directory = modes.directoryOf(session.sessionId()).orElse(project.get().path());
        var definitions = new ArrayList<>(reader.definitions(directory));
        var memberServers = project.get().group() ? memberServers(project.get()) : MemberServers.NONE;
        definitions.addAll(memberServers.definitions());
        if (!project.get().group() && !directory.equals(project.get().path())) {
            definitions.addAll(reader.overlayDefinitions(directory, project.get().path()));
        }
        var approvals = project.get().group() ? memberServers.approvals() : approvals(directory);
        var found = plugins.servers(directory, project.get().path());
        var ambiguous = new HashSet<>(ambiguousNames(found, definitions));
        ambiguous.addAll(memberServers.ambiguous());
        var pluginServers = found.stream().filter(server -> !ambiguous.contains(server.canonicalName())).collect(Collectors.toMap(PluginServer::canonicalName, server -> server));
        var aliases = aliases(configured, pluginServers);
        var result = new ArrayList<Candidate>();
        configured.forEach(name -> result.add(ambiguous.contains(name) ? Candidate.ineligible(name, AMBIGUOUS)
                : pluginServers.containsKey(name) ? pluginCandidate(name, pinned.getOrDefault(name, aliases.get(name)), pluginServers.get(name))
                : candidate(name, definitions, approvals)));
        return withoutAliasClashes(result);
    }

    private record MemberServers(List<McpServerDefinition> definitions, Map<String, McpApproval> approvals, Set<String> ambiguous) {
        static final MemberServers NONE = new MemberServers(List.of(), Map.of(), Set.of());
    }

    /**
     * What a group session gets from its members: app (overlay) servers and approved .mcp.json servers; a name that members define differently is ambiguous,
     * an identical one counts once. An unapproved .mcp.json server is listed only when no member offers a usable one under that name.
     */
    private MemberServers memberServers(Project group) {
        var usable = new ArrayList<McpServerDefinition>();
        var unapproved = new ArrayList<McpServerDefinition>();
        for (var member : projects.list().stream().filter(candidate -> Long.valueOf(group.id()).equals(candidate.groupId())).toList()) {
            var approvals = approvals(member.path());
            for (var definition : reader.definitions(member.path())) {
                if (definition.scope() == ExtensionScope.APP || definition.scope() == ExtensionScope.PROJECT && approvals.get(definition.name()) == McpApproval.APPROVED) {
                    usable.add(definition);
                } else if (definition.scope() == ExtensionScope.PROJECT) {
                    unapproved.add(definition);
                }
            }
        }
        var distinct = usable.stream().distinct().toList();
        var ambiguous = distinct.stream().collect(Collectors.groupingBy(McpServerDefinition::name, Collectors.counting())).entrySet().stream()
                .filter(count -> count.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
        var definitions = new ArrayList<>(distinct);
        var approvals = new HashMap<String, McpApproval>();
        distinct.stream().filter(definition -> definition.scope() == ExtensionScope.PROJECT).forEach(definition -> approvals.put(definition.name(), McpApproval.APPROVED));
        unapproved.stream().filter(definition -> distinct.stream().noneMatch(usableOne -> usableOne.name().equals(definition.name()))).forEach(definitions::add);
        return new MemberServers(definitions, approvals, ambiguous);
    }

    /** Canonical names that two plugins, or a plugin and a user, local or project server, would share: none of them may be used, so nothing is shadowed. */
    private static Set<String> ambiguousNames(List<PluginServer> pluginServers, List<McpServerDefinition> definitions) {
        var counts = new HashMap<String, Integer>();
        definitions.stream().map(McpServerDefinition::name).distinct().forEach(name -> counts.merge(name, 1, Integer::sum));
        pluginServers.forEach(server -> counts.merge(server.canonicalName(), 1, Integer::sum));
        return counts.entrySet().stream().filter(count -> count.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /** A script alias that two candidates end up with (a frozen alias met by a newly configured server) makes both ineligible. */
    private static List<Candidate> withoutAliasClashes(List<Candidate> candidates) {
        var counts = candidates.stream().collect(Collectors.groupingBy(Candidate::name, Collectors.counting()));
        return candidates.stream().map(candidate -> candidate.problem() == null && counts.get(candidate.name()) > 1 ? Candidate.ineligible(candidate.stored(), AMBIGUOUS) : candidate).toList();
    }

    /** Scripts see a plugin server by its key unless another configured server (plugin or not) would share that name, then by its canonical name. */
    private static Map<String, String> aliases(List<String> configured, Map<String, PluginServer> pluginServers) {
        var keyCounts = configured.stream().filter(pluginServers::containsKey).collect(Collectors.groupingBy(name -> pluginServers.get(name).key(), Collectors.counting()));
        var plainNames = configured.stream().filter(name -> !pluginServers.containsKey(name)).collect(Collectors.toSet());
        var aliases = new HashMap<String, String>();
        configured.stream().filter(pluginServers::containsKey).forEach(name -> {
            var key = pluginServers.get(name).key();
            aliases.put(name, keyCounts.get(key) == 1 && !plainNames.contains(key) && ServerAliases.allowed(key) ? key : name);
        });
        return aliases;
    }

    private static Candidate pluginCandidate(String name, String alias, PluginServer server) {
        return ServerAliases.allowed(alias) ? Candidate.eligible(alias, name, server.definition()) : Candidate.ineligible(name, ServerAliases.NAME_NOT_ALLOWED);
    }

    private static Candidate candidate(String name, List<McpServerDefinition> definitions, Map<String, McpApproval> approvals) {
        if (!ServerAliases.allowed(name)) {
            return Candidate.ineligible(name, ServerAliases.NAME_NOT_ALLOWED);
        }
        var found = definitions.stream().filter(definition -> definition.name().equals(name))
                .max(Comparator.comparingInt(definition -> PRECEDENCE.indexOf(definition.scope())));
        if (found.isEmpty()) {
            return Candidate.ineligible(name, NOT_FOUND);
        }
        if (found.get().scope() == ExtensionScope.PROJECT && approvals.get(name) != McpApproval.APPROVED) {
            return Candidate.ineligible(name, NOT_APPROVED);
        }
        return Candidate.eligible(name, name, found.get());
    }

    private Map<String, McpApproval> approvals(Path directory) {
        var approvals = new HashMap<String, McpApproval>();
        for (McpServerConfig config : mcp.servers(directory)) {
            if (config.scope() == ExtensionScope.PROJECT) {
                approvals.put(config.name(), config.approval());
            }
        }
        return approvals;
    }
}
