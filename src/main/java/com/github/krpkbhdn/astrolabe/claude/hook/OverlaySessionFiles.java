package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.astrolabe.overlay.ProjectOverlay;
import com.github.krpkbhdn.astrolabe.overlay.SessionOverlay;
import com.github.krpkbhdn.astrolabe.team.Team;
import com.github.krpkbhdn.astrolabe.team.TeamAgentsJson;
import com.github.krpkbhdn.astrolabe.team.TeamRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Writes the overlay-derived files of a session: the instructions prompt file and the agents and skills plugin directories. */
class OverlaySessionFiles {

    private static final Logger log = LoggerFactory.getLogger(OverlaySessionFiles.class);

    private final JsonMapper mapper;
    private final SessionPaths paths;
    private final int maxAgentsJsonChars;

    OverlaySessionFiles(JsonMapper mapper, SessionPaths paths, int maxAgentsJsonChars) {
        this.mapper = mapper;
        this.paths = paths;
        this.maxAgentsJsonChars = maxAgentsJsonChars;
    }

    /** One file for --append-system-prompt-file: the lead addendum of the team, then the overlay instructions; claude never gets both flags. */
    Path writePrompt(String astrolabeSessionId, SessionOverlay overlay, Team team) throws IOException {
        if (overlay.instructions().isBlank()) {
            return null;
        }
        var file = paths.promptFileOf(astrolabeSessionId);
        OwnerOnlyFiles.write(file, (team == null ? "" : TeamAgentsJson.leadAddendum(team) + "\n\n") + overlay.instructions());
        return file;
    }

    /** Agents too long for the command line travel as a plugin directory; claude then names them {@code astrolabe-agent:<name>} instead of {@code <name>}. */
    Path writeAgentsPlugin(String astrolabeSessionId, SessionOverlay overlay, Team team) throws IOException {
        if (overlay.agents().isEmpty() || commandLineLength(TeamAgentsJson.agents(team, overlay.agents().values())) <= maxAgentsJsonChars) {
            return null;
        }
        log.warn("The overlay agents do not fit into --agents, they are passed as a plugin directory");
        var teamNames = team == null ? Set.<String>of() : team.subagents().stream().map(TeamRole::name).collect(Collectors.toSet());
        var directory = paths.agentsPluginOf(astrolabeSessionId);
        OwnerOnlyFiles.createDirectory(directory.resolve(".claude-plugin"));
        OwnerOnlyFiles.createDirectory(directory.resolve("agents"));
        OwnerOnlyFiles.write(directory.resolve(".claude-plugin").resolve("plugin.json"),
                mapper.writeValueAsString(mapper.createObjectNode().put("name", "astrolabe-agent").put("description", "Agents of the project overlay")));
        for (var agent : overlay.agents().values()) {
            if (!teamNames.contains(agent.name())) {
                OwnerOnlyFiles.write(directory.resolve("agents").resolve(agent.name() + ".md"), agent.markdown());
            }
        }
        return directory;
    }

    /**
     * The skills of the overlay context folders travel as a plugin directory instead of --add-dir, which makes claude ask to trust the folder in every session;
     * claude names them {@code astrolabe-skill:<skill>}. A skill name found in an earlier folder wins. Files are copied (no symlinks on Windows).
     */
    Path writeSkillsPlugin(String astrolabeSessionId, SessionOverlay overlay) throws IOException {
        var sources = new LinkedHashMap<String, Path>();
        for (var context : overlay.contextDirectories()) {
            var skills = context.resolve(ProjectOverlay.SKILLS_DIRECTORY);
            if (!Files.isDirectory(skills)) {
                continue;
            }
            try (var entries = Files.list(skills)) {
                entries.filter(Files::isDirectory).sorted().forEach(skill -> sources.putIfAbsent(skill.getFileName().toString(), skill));
            }
        }
        if (sources.isEmpty()) {
            return null;
        }
        var directory = paths.skillsPluginOf(astrolabeSessionId);
        OwnerOnlyFiles.createDirectory(directory.resolve(".claude-plugin"));
        OwnerOnlyFiles.write(directory.resolve(".claude-plugin").resolve("plugin.json"),
                mapper.writeValueAsString(mapper.createObjectNode().put("name", "astrolabe-skill").put("description", "Skills of the project overlay")));
        for (var source : sources.entrySet()) {
            copyTree(source.getValue(), directory.resolve("skills").resolve(source.getKey()));
        }
        return directory;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var tree = Files.walk(source)) {
            for (var path : tree.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    OwnerOnlyFiles.createDirectory(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Windows quoting puts a backslash before every quote and may double backslashes, so each of them counts twice. */
    private static int commandLineLength(String argument) {
        return argument.length() + (int) argument.chars().filter(c -> c == '"' || c == '\\').count();
    }
}
