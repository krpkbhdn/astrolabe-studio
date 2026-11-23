package com.github.krpkbhdn.astrolabe.ui.chrome

import androidx.compose.runtime.Composable
import com.github.krpkbhdn.astrolabe.ui.components.Banner
import com.github.krpkbhdn.astrolabe.ui.components.GhostButton
import com.github.krpkbhdn.astrolabe.ui.components.PrimaryButton
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.state.UpdateState
import com.github.krpkbhdn.astrolabe.ui.state.UpdateStatus
import com.github.krpkbhdn.astrolabe.ui.state.installingMessage
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private const val PERCENT = 100

/** Strip above the content: offers a new release, shows the download and asks for the restart that applies it. */
@Composable
fun UpdateBanner(update: UpdateState) {
    when (val status = update.status) {
        UpdateStatus.None -> Unit
        is UpdateStatus.Available -> if (!update.dismissed) {
            val action = update.actionFor(status.update)
            Banner("Version ${status.update.version} is available${action.note?.let { ". $it" }.orEmpty()}", colors.accent, Icons.Download) {
                GhostButton("Later", update::dismiss)
                PrimaryButton(action.label, if (action.installs) update::download else update::openReleasePage)
            }
        }
        is UpdateStatus.Downloading -> {
            val percent = status.fraction?.let { " ${(it * PERCENT).toInt()}%" }.orEmpty()
            Banner("Downloading version ${status.update.version}$percent", colors.accent, Icons.Download) {
                PrimaryButton("Downloading", {}, enabled = false, loading = true)
            }
        }
        is UpdateStatus.Preparing -> Banner("Preparing version ${status.update.version}...", colors.accent, Icons.Download) {
            PrimaryButton("Preparing", {}, enabled = false, loading = true)
        }
        is UpdateStatus.Installing -> Banner(installingMessage(status.update.version), colors.accent, Icons.Refresh) {
            PrimaryButton("Installing", {}, enabled = false, loading = true)
        }
        is UpdateStatus.Ready -> Banner("Version ${status.update.version} is downloaded. Install it and restart to apply the update", colors.success, Icons.Refresh) {
            PrimaryButton("Install and restart", update::restart)
        }
    }
}
