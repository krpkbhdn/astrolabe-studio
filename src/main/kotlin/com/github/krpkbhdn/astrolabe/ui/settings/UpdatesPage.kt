package com.github.krpkbhdn.astrolabe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.state.UpdateState
import com.github.krpkbhdn.astrolabe.ui.state.UpdateStatus
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing

private const val PERCENT = 100

/** One line saying where the self-update stands; the buttons beside it come from [UpdateState]. */
internal fun updateSummary(update: UpdateState): String = when (val status = update.status) {
    is UpdateStatus.Available -> "Version ${status.update.version} is available.${update.actionFor(status.update).note?.let { " $it." }.orEmpty()}"
    is UpdateStatus.Downloading -> "Downloading version ${status.update.version}${status.fraction?.let { " ${(it * PERCENT).toInt()}%" }.orEmpty()}"
    is UpdateStatus.Preparing -> "Preparing version ${status.update.version}..."
    is UpdateStatus.Ready -> "Version ${status.update.version} is downloaded. Install it and restart to apply it."
    UpdateStatus.None -> when {
        !update.enabled -> "Update checks are off in a run from sources."
        update.checking -> "Checking for updates..."
        update.checkFailed -> "Could not check for updates."
        update.upToDate -> "You have the latest version."
        else -> "Checked automatically every few hours."
    }
}

@Composable
fun UpdatesPage(update: UpdateState) {
    SettingGroup("Version") {
        SettingRow("Current version", update.currentVersion.ifBlank { "Not a release build" }) {}
        SettingRow("Status", updateSummary(update)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { UpdateButtons(update) }
        }
    }
}

@Composable
private fun UpdateButtons(update: UpdateState) {
    when (val status = update.status) {
        is UpdateStatus.Available -> {
            val action = update.actionFor(status.update)
            if (action.installs) {
                AppButton("Release notes", update::openReleasePage)
                AppButton(action.label, update::download, variant = ButtonVariant.PRIMARY)
            } else {
                AppButton(action.label, update::openReleasePage, variant = ButtonVariant.PRIMARY)
            }
        }
        is UpdateStatus.Downloading -> AppButton("Downloading", {}, variant = ButtonVariant.PRIMARY, enabled = false, loading = true)
        is UpdateStatus.Preparing -> AppButton("Preparing", {}, variant = ButtonVariant.PRIMARY, enabled = false, loading = true)
        is UpdateStatus.Ready -> AppButton("Install and restart", update::restart, variant = ButtonVariant.PRIMARY)
        UpdateStatus.None -> AppButton(if (update.checkFailed) "Retry" else "Check for updates", update::checkNow, enabled = update.enabled && !update.checking, loading = update.checking)
    }
}
