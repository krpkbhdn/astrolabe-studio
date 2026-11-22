package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.update.UpdateOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UpdateModelTest {
    @Test
    fun `replacing an open confirm cancels the previous one once and keeps the new one`() {
        var cancelled = 0
        val previous = ConfirmRequest("Old", "m", "Ok", onCancel = { cancelled++ }) {}
        val next = ConfirmRequest("New", "m", "Ok") {}

        assertThat(supersede(previous, next)).isSameAs(next)
        assertThat(cancelled).isEqualTo(1)
        assertThat(supersede(null, next)).isSameAs(next)
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun `an installable update offers install and restart`() {
        assertThat(updateActionOf(installable = true, needsAuthentication = false)).isEqualTo(UpdateAction("Install and restart", true, null))
    }

    @Test
    fun `a package update notes the administrator password`() {
        assertThat(updateActionOf(true, true).note).isEqualTo("Asks for your administrator password")
    }

    @Test
    fun `an update that cannot be installed only opens the release page`() {
        assertThat(updateActionOf(false, true)).isEqualTo(UpdateAction("Open release page", false, null))
    }

    @Test
    fun `a successful earlier update stays quiet`() {
        assertThat(outcomeToast(UpdateOutcome(UpdateOutcome.Status.OK, ""))).isNull()
    }

    @Test
    fun `a cancelled or failed earlier update is explained`() {
        assertThat(outcomeToast(UpdateOutcome(UpdateOutcome.Status.AUTH_CANCELLED, ""))).contains("authorization cancelled").contains("update.log")
        assertThat(outcomeToast(UpdateOutcome(UpdateOutcome.Status.FAILED, "install"))).contains("failed: install").contains("update.log")
    }

    @Test
    fun `the install confirm names the update and the closing of sessions`() {
        assertThat(installConfirmMessage("2.4.0", false)).isEqualTo("Install update 2.4.0 and restart? All running sessions will be closed.")
    }

    @Test
    fun `the install confirm of a package update mentions the administrator password`() {
        assertThat(installConfirmMessage("2.4.0", true)).endsWith("You will be asked for your administrator password.")
    }
}
