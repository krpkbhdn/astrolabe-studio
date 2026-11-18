package com.github.krpkbhdn.astrolabe.codemode.policy;

import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.astrolabe.codemode.SessionContext;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Matches the {@code mcp__<server>}, {@code mcp__<server>__*} and {@code mcp__<server>__<tool>} rules of the user's Claude Code settings.
 * Allow, deny and ask rules come from the user settings and the session folder's project and local settings, as Claude Code itself reads them
 * (the user wrote or accepted those files). Managed settings are not read.
 * Anything not understood fails closed: another wildcard form in a deny or ask rule of the server, or a file that cannot be read, turns an allow into ASK.
 */
@Component
public class ClaudePermissionRules implements PermissionRules {

    private static final Logger log = LoggerFactory.getLogger(ClaudePermissionRules.class);
    private static final String PREFIX = "mcp__";
    private static final String SEPARATOR = "__";
    private static final String WILDCARD = "*";
    private static final java.util.regex.Pattern UNSAFE_NAME_CHARACTERS = java.util.regex.Pattern.compile("[^A-Za-z0-9_-]");
    private static final String GLOB_CHARACTERS = "*?[";
    private static final String[] SETTINGS_FILES = {".claude/settings.json", ".claude/settings.local.json"};
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Settings(Set<String> allow, Set<String> deny, Set<String> ask, boolean complete) {
    }

    private final ProjectService projects;
    private final ProjectCodeModeService modes;
    private final HostEnvironment host;

    public ClaudePermissionRules(ProjectService projects, ProjectCodeModeService modes, HostEnvironment host) {
        this.projects = projects;
        this.modes = modes;
        this.host = host;
    }

    /** Members' deny and ask rules also hold in a group session (stricter only); their allow rules do not. */
    private List<Project> groupMembers(long projectId) {
        return projects.find(projectId).filter(Project::group).map(group -> projects.list().stream().filter(member -> Long.valueOf(group.id()).equals(member.groupId())).toList())
                .orElse(List.of());
    }

    @Override
    public Verdict verdict(SessionContext session, String server, String tool) {
        var directory = modes.directoryOf(session.sessionId()).or(() -> projects.find(session.projectId()).map(project -> project.path()));
        var allow = new HashSet<String>();
        var deny = new HashSet<String>();
        var ask = new HashSet<String>();
        var complete = directory.isPresent();
        var user = read(host.claudeConfigDirectory().resolve("settings.json"));
        allow.addAll(user.allow());
        complete &= user.complete();
        deny.addAll(user.deny());
        ask.addAll(user.ask());
        if (directory.isPresent()) {
            for (var file : SETTINGS_FILES) {
                var repository = read(directory.get().resolve(file));
                allow.addAll(repository.allow());
                deny.addAll(repository.deny());
                ask.addAll(repository.ask());
                complete &= repository.complete();
            }
        }
        for (var member : groupMembers(session.projectId())) {
            for (var file : SETTINGS_FILES) {
                var repository = read(member.path().resolve(file));
                deny.addAll(repository.deny());
                ask.addAll(repository.ask());
                complete &= repository.complete();
            }
        }
        var wholeServer = PREFIX + server + SEPARATOR + WILDCARD;
        var exactServer = PREFIX + server;
        var exactTool = PREFIX + server + SEPARATOR + tool;
        var normalizedTool = PREFIX + server + SEPARATOR + UNSAFE_NAME_CHARACTERS.matcher(tool).replaceAll("_");
        if (deny.contains(exactServer) || deny.contains(wholeServer) || deny.contains(exactTool) || deny.contains(normalizedTool)) {
            return Verdict.DENY;
        }
        if (ask.contains(exactServer) || ask.contains(wholeServer) || ask.contains(exactTool) || ask.contains(normalizedTool) || hasOtherPattern(deny, server)
                || hasOtherPattern(ask, server)) {
            return Verdict.ASK;
        }
        if (allow.contains(exactServer) || allow.contains(wholeServer) || allow.contains(exactTool)) {
            return complete ? Verdict.ALLOW : Verdict.ASK;
        }
        return Verdict.UNDECIDED;
    }

    /** A wildcard rule that may reach the server's tools without being the plain whole-server form, such as {@code mcp__x__delete_*} or {@code mcp__*}. */
    private static boolean hasOtherPattern(Set<String> rules, String server) {
        var serverPrefix = PREFIX + server + SEPARATOR;
        return rules.stream().filter(rule -> !rule.equals(serverPrefix + WILDCARD)).anyMatch(rule -> {
            var at = firstGlob(rule);
            return at >= 0 && (rule.substring(0, at).startsWith(serverPrefix) || serverPrefix.startsWith(rule.substring(0, at)));
        });
    }

    private static int firstGlob(String rule) {
        for (int i = 0; i < rule.length(); i++) {
            if (GLOB_CHARACTERS.indexOf(rule.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private Settings read(Path file) {
        var none = new Settings(Set.of(), Set.of(), Set.of(), true);
        try {
            if (!Files.isRegularFile(file)) {
                return none;
            }
            if (Files.size(file) > CodeModeProperties.MAX_SETTINGS_BYTES) {
                log.debug("Claude settings {} skipped: too large", file.getFileName());
                return new Settings(Set.of(), Set.of(), Set.of(), false);
            }
            var permissions = MAPPER.readTree(Files.readString(file)).path("permissions");
            return new Settings(rules(permissions.path("allow")), rules(permissions.path("deny")), rules(permissions.path("ask")), true);
        } catch (IOException | RuntimeException e) {
            log.debug("Claude settings {} not read: {}", file.getFileName(), e.getClass().getSimpleName());
            return new Settings(Set.of(), Set.of(), Set.of(), false);
        }
    }

    private static Set<String> rules(tools.jackson.databind.JsonNode array) {
        var rules = new HashSet<String>();
        array.forEach(rule -> rules.add(rule.asString("").strip()));
        return rules;
    }
}
