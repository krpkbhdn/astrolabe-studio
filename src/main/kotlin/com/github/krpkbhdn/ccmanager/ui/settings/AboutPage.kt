package com.github.krpkbhdn.ccmanager.ui.settings

import androidx.compose.runtime.Composable
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.state.AppState
import java.awt.Desktop
import java.net.URI

private const val REPOSITORY_URL = "https://github.com/krpkbhdn/cc-manager"
private const val LINKEDIN_URL = "https://www.linkedin.com/in/krpkbhdn/"
private const val DONATE_URL = "https://ko-fi.com/krpkbhdn"

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

@Composable
fun AboutPage(state: AppState) {
    SettingGroup("Claude Code Manager") {
        SettingRow("Version", state.update.currentVersion.ifBlank { "Not a release build" }) {}
        SettingRow("Releases", "Release notes and installers on GitHub.") {
            AppButton("Open releases", state.update::openReleases, enabled = state.update.enabled)
        }
        SettingRow("Source code", REPOSITORY_URL) {
            AppButton("Open GitHub", { openInBrowser(REPOSITORY_URL) })
        }
    }
    SettingGroup("Author") {
        SettingRow("Bohdan Krupko", LINKEDIN_URL) {
            AppButton("Open LinkedIn", { openInBrowser(LINKEDIN_URL) })
        }
    }
    SettingGroup("Support") {
        SettingRow("Support the project and the author", "If the app saves you time, a coffee keeps it going. Thank you!") {
            AppButton("Donate on Ko-fi", { openInBrowser(DONATE_URL) })
        }
    }
    state.environment.installation?.takeIf { it.installed }?.let { claude ->
        SettingGroup("Claude Code") {
            SettingRow("Installed version", claude.version.orEmpty().ifBlank { "Unknown" }) {}
            SettingRow("Login", claude.login.name.lowercase().replace('_', ' ')) {}
        }
    }
}
