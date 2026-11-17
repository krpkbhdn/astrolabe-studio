package com.github.krpkbhdn.ccmanager.codemode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What a group inherits: the Code Mode members' servers, with the most restrictive tool call access; a member's allow list never widens it. */
final class GroupCodeModeMerge {

    private GroupCodeModeMerge() {
    }

    static ProjectCodeMode of(long groupId, List<ProjectCodeMode> members) {
        var active = members.stream().filter(member -> member.enabled() && !member.servers().isEmpty()).toList();
        var servers = new LinkedHashSet<String>();
        active.forEach(member -> servers.addAll(member.servers()));
        return new ProjectCodeMode(groupId, !active.isEmpty(), List.copyOf(servers), access(active));
    }

    private static ToolCallAccess access(List<ProjectCodeMode> active) {
        var accesses = active.stream().map(ProjectCodeMode::toolCalls).toList();
        if (accesses.stream().anyMatch(access -> access.mode() == ToolCallAccess.Mode.ASK)) {
            return ToolCallAccess.ASK;
        }
        Set<String> allowed = null;
        for (var access : accesses) {
            if (access.mode() == ToolCallAccess.Mode.SELECTED) {
                if (allowed == null) {
                    allowed = new LinkedHashSet<>(access.servers());
                } else {
                    allowed.retainAll(access.servers());
                }
            }
        }
        if (allowed == null) {
            return accesses.isEmpty() ? ToolCallAccess.ASK : ToolCallAccess.ALL;
        }
        return allowed.isEmpty() ? ToolCallAccess.ASK : ToolCallAccess.selected(List.copyOf(allowed));
    }
}
