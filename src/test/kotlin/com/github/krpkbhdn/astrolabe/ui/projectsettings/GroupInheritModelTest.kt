package com.github.krpkbhdn.astrolabe.ui.projectsettings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GroupInheritModelTest {
    @Test
    fun `a group opens only Languages and Code Mode`() {
        assertThat(sectionsOf(true)).containsExactly(ProjectSettingsSection.LANGUAGES, ProjectSettingsSection.CODE_MODE)
        assertThat(sectionsOf(false)).isEqualTo(ProjectSettingsSection.entries)
    }

    @Test
    fun `a section a group does not have falls back to its first one`() {
        assertThat(sectionFor(true, ProjectSettingsSection.PLUGINS)).isEqualTo(ProjectSettingsSection.LANGUAGES)
        assertThat(sectionFor(true, ProjectSettingsSection.CODE_MODE)).isEqualTo(ProjectSettingsSection.CODE_MODE)
        assertThat(sectionFor(false, ProjectSettingsSection.PLUGINS)).isEqualTo(ProjectSettingsSection.PLUGINS)
    }

    @Test
    fun `sources list every member an item comes from in member order`() {
        val sources = sourcesOf(listOf("api" to listOf("github", "jira"), "web" to listOf("github")))

        assertThat(sources["github"]).containsExactly("api", "web")
        assertThat(sources["jira"]).containsExactly("api")
        assertThat(sourceNoteOf(sources["github"])).isEqualTo("From api, web")
        assertThat(sourceNoteOf(sources["missing"])).isNull()
    }

    @Test
    fun `inheriting again is confirmed and runs the action only on confirm`() {
        var inherited = false
        val request = inheritConfirmOf("Code Mode settings") { inherited = true }

        assertThat(request.title).isEqualTo("Inherit Code Mode settings from the projects?")
        assertThat(inherited).isFalse()
        request.onConfirm()
        assertThat(inherited).isTrue()
    }
}
