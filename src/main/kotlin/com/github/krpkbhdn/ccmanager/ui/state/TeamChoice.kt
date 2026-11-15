package com.github.krpkbhdn.ccmanager.ui.state

import com.github.krpkbhdn.ccmanager.team.Team
import com.github.krpkbhdn.ccmanager.ui.components.MenuEntry

/** The teams a launch button offers: [default] is the project's own default (null: none set, launch without a team), [makeDefault] stores a choice, null clearing it. */
class TeamChoice(val teams: List<Team>, val default: Team?, val makeDefault: (Team?) -> Unit)

/** "<verb> without team" and "<verb> with <team>" rows marked like the default IDE; choosing one calls [launch] with it. */
fun teamMenuEntries(choice: TeamChoice, verb: String, launch: (Team?) -> Unit): List<MenuEntry> {
    val noTeam = MenuEntry("$verb without team", trailing = defaultMarker(choice.default == null) { choice.makeDefault(null) }, onSelect = { launch(null) })
    return listOf(noTeam) + choice.teams.map { team ->
        MenuEntry("$verb with ${team.name}", trailing = defaultMarker(team.id() == choice.default?.id()) { choice.makeDefault(team) }, onSelect = { launch(team) })
    }
}
