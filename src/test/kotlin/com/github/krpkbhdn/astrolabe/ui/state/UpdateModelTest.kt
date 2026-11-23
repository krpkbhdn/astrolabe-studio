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
    fun `a successful earlier update names the version the app runs now`() {
        assertThat(outcomeToast(UpdateOutcome(UpdateOutcome.Status.OK, ""), "2.4.0", "/data/updates")).isEqualTo("Updated to version 2.4.0")
    }

    @Test
    fun `a cancelled or failed earlier update is explained and names both logs`() {
        val cancelled = outcomeToast(UpdateOutcome(UpdateOutcome.Status.AUTH_CANCELLED, ""), "2.4.0", "/data/updates")
        val failed = outcomeToast(UpdateOutcome(UpdateOutcome.Status.FAILED, "install"), "2.4.0", "/data/updates")

        assertThat(cancelled).contains("authorization cancelled").contains("update.log").contains("relaunch.log")
        assertThat(failed).contains("failed: install").contains("update.log").contains("relaunch.log").contains("updates")
    }

    @Test
    fun `the installing message says the app closes and restarts on its own`() {
        assertThat(installingMessage("2.4.0")).isEqualTo("Installing version 2.4.0. The app will close and restart on its own.")
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
