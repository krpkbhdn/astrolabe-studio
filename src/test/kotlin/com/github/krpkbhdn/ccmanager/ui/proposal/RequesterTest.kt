package com.github.krpkbhdn.ccmanager.ui.proposal

import com.github.krpkbhdn.ccmanager.claude.proposal.PermissionProposal
import com.github.krpkbhdn.ccmanager.claude.proposal.PermissionProposal.Origin
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class RequesterTest {
    private fun proposal(tool: String, origin: Origin) =
        PermissionProposal("p", "s", Instant.EPOCH, "", tool, JsonMapper.builder().build().createObjectNode(), JsonMapper.builder().build().createObjectNode(), emptyList(), origin)

    @Test
    fun assistantOriginIsLabelledAsTheAssistant() {
        assertThat(requesterOf(proposal("anything", Origin.ASSISTANT))).isEqualTo("the Assistant through cc-manager-app")
    }

    @Test
    fun codeModeOriginIsLabelledAsAScript() {
        assertThat(requesterOf(proposal("db.query", Origin.CODE_MODE))).isEqualTo("a Code Mode script")
    }

    @Test
    fun aSpoofedToolNameNeverChangesTheRequester() {
        assertThat(requesterOf(proposal("mcp__cc-manager-app__open_project", Origin.HOOK))).isEqualTo("cc-manager")
        assertThat(requesterOf(proposal("db.query", Origin.HOOK))).isEqualTo("cc-manager")
        assertThat(requesterOf(proposal("mcp__x__y", Origin.CODE_MODE))).isEqualTo("a Code Mode script")
    }
}
