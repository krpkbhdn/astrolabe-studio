package com.github.krpkbhdn.astrolabe.codemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroupCodeModeMergeTest {

    private static ProjectCodeMode member(long id, boolean enabled, ToolCallAccess access, String... servers) {
        return new ProjectCodeMode(id, enabled, List.of(servers), access);
    }

    private static ToolCallAccess merged(ProjectCodeMode... members) {
        return GroupCodeModeMerge.of(9, List.of(members)).toolCalls();
    }

    @Test
    void theServersAreTheOrderedDeduplicatedUnionOfTheEnabledMembersWithServers() {
        var merged = GroupCodeModeMerge.of(9, List.of(member(1, true, ToolCallAccess.ASK, "a", "b"), member(2, false, ToolCallAccess.ASK, "x"),
                member(3, true, ToolCallAccess.ASK), member(4, true, ToolCallAccess.ASK, "b", "c")));

        assertThat(merged.enabled()).isTrue();
        assertThat(merged.projectId()).isEqualTo(9);
        assertThat(merged.servers()).containsExactly("a", "b", "c");
    }

    @Test
    void noCountingMemberMeansDisabledAndAsk() {
        var merged = GroupCodeModeMerge.of(9, List.of(member(1, false, ToolCallAccess.ALL, "a"), member(2, true, ToolCallAccess.ALL)));

        assertThat(merged.enabled()).isFalse();
        assertThat(merged.servers()).isEmpty();
        assertThat(merged.toolCalls()).isEqualTo(ToolCallAccess.ASK);
        assertThat(GroupCodeModeMerge.of(9, List.of()).enabled()).isFalse();
    }

    @Test
    void theMostRestrictiveAccessWins() {
        assertThat(merged(member(1, true, ToolCallAccess.ALL, "a"), member(2, true, ToolCallAccess.ASK, "b"))).isEqualTo(ToolCallAccess.ASK);
        assertThat(merged(member(1, true, ToolCallAccess.ALL, "a"), member(2, true, ToolCallAccess.ALL, "b"))).isEqualTo(ToolCallAccess.ALL);
        assertThat(merged(member(1, true, ToolCallAccess.ALL, "a"), member(2, true, ToolCallAccess.selected(List.of("a", "b")), "b")))
                .isEqualTo(ToolCallAccess.selected(List.of("a", "b")));
        assertThat(merged(member(1, true, ToolCallAccess.selected(List.of("a", "b")), "a"), member(2, true, ToolCallAccess.selected(List.of("b", "c")), "b")))
                .isEqualTo(ToolCallAccess.selected(List.of("b")));
        assertThat(merged(member(1, true, ToolCallAccess.selected(List.of("a")), "a"), member(2, true, ToolCallAccess.selected(List.of("c")), "c")))
                .isEqualTo(ToolCallAccess.ASK);
    }

    @Test
    void aMemberWithoutCodeModeNeitherRestrictsNorWidens() {
        assertThat(merged(member(1, true, ToolCallAccess.ALL, "a"), member(2, false, ToolCallAccess.ASK, "b"))).isEqualTo(ToolCallAccess.ALL);
    }
}
