package com.github.krpkbhdn.astrolabe.ui.tools

import androidx.compose.runtime.Composable
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.TextLink

/**
 * Nothing, or the install button (plus a Website link for a tool without an automatic command); a blocked reason is a tooltip unless the caller shows it elsewhere.
 * A tool downloaded by the app shows Update (the install flow again) and Uninstall instead.
 */
@Composable
fun InstallButtonView(
    button: InstallButton,
    installLabel: String,
    onInstall: () -> Unit,
    onWebsite: (url: String) -> Unit,
    onUninstall: () -> Unit,
    reasonShownElsewhere: Boolean = false,
) {
    when (button) {
        is InstallButton.Install -> {
            AppButton(
                installLabel, onInstall,
                variant = if (button.enabled || !reasonShownElsewhere) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
                compact = true, enabled = button.enabled, tooltip = button.reason.takeUnless { reasonShownElsewhere },
            )
            button.websiteUrl?.let { url -> TextLink("Website") { onWebsite(url) } }
        }
        is InstallButton.Manage -> {
            AppButton("Update", onInstall, compact = true, enabled = button.updateEnabled, tooltip = button.updateReason)
            AppButton("Uninstall", onUninstall, compact = true)
        }
        InstallButton.Hidden -> Unit
    }
}
