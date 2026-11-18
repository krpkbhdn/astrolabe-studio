package com.github.krpkbhdn.astrolabe.ui.trust

import com.github.krpkbhdn.astrolabe.project.ModelSelection
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.project.TrustLevel
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrustIndicatorModelTest {

    private fun project(override: TrustLevel? = null, group: Boolean = false) =
        Project(1, "p", Path.of("p"), Instant.now(), ModelSelection.DEFAULT, group, null, override)

    @Test
    fun `follows the app default without an override`() {
        val indicator = trustIndicatorOf(project(), TrustLevel.TRUSTED)!!

        assertThat(indicator.label).isEqualTo("Trusted")
        assertThat(indicator.tone).isEqualTo(TrustTone.GOOD)
        assertThat(indicator.lines.first()).contains("app default")
        assertThat(indicator.options.map { it.label }).containsExactly("App default (Trusted)", "Ask", "Trusted", "Autonomous")
        assertThat(indicator.options.filter { it.checked }.map { it.level }).containsExactly(null)
    }

    @Test
    fun `an override wins and is the checked option`() {
        val indicator = trustIndicatorOf(project(TrustLevel.AUTONOMOUS), TrustLevel.ASK)!!

        assertThat(indicator.label).isEqualTo("Auto")
        assertThat(indicator.tone).isEqualTo(TrustTone.DANGER)
        assertThat(indicator.lines.first()).contains("this project")
        assertThat(indicator.options.filter { it.checked }.map { it.level }).containsExactly(TrustLevel.AUTONOMOUS)
    }

    @Test
    fun `ask is neutral`() {
        assertThat(trustIndicatorOf(project(TrustLevel.ASK), TrustLevel.TRUSTED)!!.tone).isEqualTo(TrustTone.NEUTRAL)
    }

    @Test
    fun `hidden without a project and for a group`() {
        assertThat(trustIndicatorOf(null, TrustLevel.TRUSTED)).isNull()
        assertThat(trustIndicatorOf(project(group = true), TrustLevel.TRUSTED)).isNull()
    }
}
