package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.update.UpdateOutcome
import java.nio.file.Path

private const val INSTALL_LABEL = "Install and restart"
private const val RELEASE_PAGE_LABEL = "Open release page"
private const val ADMIN_NOTE = "Asks for your administrator password"

/** What the update offer shows: the button [label], whether it installs (else it opens the release page) and an optional [note]. */
data class UpdateAction(val label: String, val installs: Boolean, val note: String?)

fun updateActionOf(installable: Boolean, needsAuthentication: Boolean): UpdateAction =
    if (installable) UpdateAction(INSTALL_LABEL, true, ADMIN_NOTE.takeIf { needsAuthentication }) else UpdateAction(RELEASE_PAGE_LABEL, false, null)

private const val PASSWORD_NOTE = " You will be asked for your administrator password."

/** The confirm shown before the app quits to install: it names the update and says what the restart costs. */
fun installConfirmMessage(version: String, needsAuthentication: Boolean): String =
    "Install update $version and restart? All running sessions will be closed." + PASSWORD_NOTE.takeIf { needsAuthentication }.orEmpty()

/** The message about the previous update run; a failure names the log files in [updatesDirectory], a success the version the app runs now. */
fun outcomeToast(outcome: UpdateOutcome, currentVersion: String, updatesDirectory: String): String {
    val logs = "see ${Path.of(updatesDirectory, "update.log")} and ${Path.of(updatesDirectory, "relaunch.log")}"
    return when (outcome.status()) {
        UpdateOutcome.Status.OK -> "Updated to version $currentVersion"
        UpdateOutcome.Status.AUTH_CANCELLED -> "Update was not installed (authorization cancelled, $logs)"
        UpdateOutcome.Status.FAILED -> "Update was not installed (failed${outcome.step().takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}, $logs)"
    }
}

/** The line shown from the confirmed install until the window closes. */
fun installingMessage(version: String): String = "Installing version $version. The app will close and restart on its own."
