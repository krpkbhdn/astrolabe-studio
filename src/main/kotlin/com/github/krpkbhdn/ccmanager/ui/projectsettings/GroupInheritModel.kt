package com.github.krpkbhdn.ccmanager.ui.projectsettings

import com.github.krpkbhdn.ccmanager.ui.state.ConfirmRequest

const val INHERIT_TITLE = "Inherit from projects"
const val INHERIT_ON_NOTE = "Shows what the projects in this group have; read-only. Switch off to edit it for the group alone."
const val INHERIT_OFF_NOTE = "The group has its own settings, independent of its projects."

/** Sections a group can open: only those that work for a group row. */
fun sectionsOf(group: Boolean): List<ProjectSettingsSection> =
    if (group) listOf(ProjectSettingsSection.LANGUAGES, ProjectSettingsSection.CODE_MODE) else ProjectSettingsSection.entries

/** The requested section, or the first one the project or group has. */
fun sectionFor(group: Boolean, requested: ProjectSettingsSection): ProjectSettingsSection =
    requested.takeIf { it in sectionsOf(group) } ?: sectionsOf(group).first()

/** Item (server, language) to the names of the members it comes from, in member order. */
fun sourcesOf(itemsByMember: List<Pair<String, Collection<String>>>): Map<String, List<String>> =
    itemsByMember.flatMap { (member, items) -> items.map { it to member } }.groupBy({ it.first }, { it.second })

fun sourceNoteOf(members: List<String>?): String? = members?.takeIf { it.isNotEmpty() }?.let { "From ${it.joinToString()}" }

fun inheritNoteOf(inherits: Boolean): String = if (inherits) INHERIT_ON_NOTE else INHERIT_OFF_NOTE

/** Going back to inheriting throws the group's own settings away, so it is confirmed first. */
fun inheritConfirmOf(what: String, onConfirm: () -> Unit) = ConfirmRequest(
    "Inherit $what from the projects?",
    "The $what set for this group are discarded and the projects' ones apply again.",
    "Inherit",
    onConfirm = onConfirm,
)
