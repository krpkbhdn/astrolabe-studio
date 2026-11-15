package com.github.krpkbhdn.ccmanager.claude.proposal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/** Decides which permission requests the app allows by itself; questions, plans and forms always go to the user. */
final class TrustPolicy {

    private static final Set<String> READ_ONLY_TOOLS = Set.of("Read", "Glob", "Grep");
    private static final String READ_TOOL = "Read";
    private static final String GLOB_TOOL = "Glob";
    /** Covers cc-manager, cc-manager-app and cc-manager-code: the app's own tools keep their own consent. */
    private static final String APP_TOOL_PREFIX = "mcp__cc-manager";

    private TrustPolicy() {}

    static boolean autoAllows(SessionTrust.Grant grant, PermissionProposal proposal) {
        return switch (grant.level()) {
            case ASK -> false;
            case TRUSTED -> READ_ONLY_TOOLS.contains(proposal.toolName()) && staysInside(grant.directory(), proposal);
            case AUTONOMOUS -> !proposal.toolName().startsWith(APP_TOOL_PREFIX);
        };
    }

    private static boolean staysInside(Path directory, PermissionProposal proposal) {
        if (directory == null) {
            return false;
        }
        var input = proposal.toolInput();
        var read = READ_TOOL.equals(proposal.toolName());
        if (GLOB_TOOL.equals(proposal.toolName()) && leavesFolder(input.path("pattern").asString(""))) {
            return false;
        }
        var target = input.path(read ? "file_path" : "path").asString("");
        return target.isEmpty() ? !read : isInside(directory, target);
    }

    private static boolean leavesFolder(String pattern) {
        return pattern.startsWith("~") || pattern.startsWith("/") || pattern.startsWith("\\") || pattern.matches(".*(^|[/\\\\])\\.\\.([/\\\\]|$).*")
                || pattern.matches("^[A-Za-z]:.*");
    }

    private static boolean isInside(Path directory, String target) {
        try {
            var base = real(directory);
            var path = Path.of(target);
            return real(path.isAbsolute() ? path : base.resolve(path)).startsWith(base);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** A path that does not exist yet cannot be resolved through symlinks, so it is only normalized. */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
