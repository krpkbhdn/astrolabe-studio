package com.github.krpkbhdn.ccmanager.ui.state

import com.github.krpkbhdn.ccmanager.team.Team
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TeamChoiceTest {
    private val dev = Team(1, "Dev team", emptyList())
    private val docs = Team(2, "Docs", emptyList())

    @Test
    fun `menu offers no team first and then every team, each with a default marker`() {
        val entries = teamMenuEntries(TeamChoice(listOf(dev, docs), dev) {}, "Resume") {}

        assertThat(entries.map { it.label }).containsExactly("Resume without team", "Resume with Dev team", "Resume with Docs")
        assertThat(entries).allSatisfy { assertThat(it.trailing).isNotNull() }
    }

    @Test
    fun `selecting a row launches with that team and no team for the first row`() {
        val chosen = mutableListOf<Team?>()
        val entries = teamMenuEntries(TeamChoice(listOf(dev, docs), dev) {}, "Resume") { chosen += it }

        entries.forEach { it.onSelect() }

        assertThat(chosen).containsExactly(null, dev, docs)
    }

    @Test
    fun `menu without teams still offers no team`() {
        assertThat(teamMenuEntries(TeamChoice(emptyList(), null) {}, "Launch") {}.map { it.label }).containsExactly("Launch without team")
    }
}
