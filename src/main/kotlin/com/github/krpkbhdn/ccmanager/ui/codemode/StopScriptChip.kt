package com.github.krpkbhdn.ccmanager.ui.codemode

import androidx.compose.runtime.Composable
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.components.ButtonVariant
import com.github.krpkbhdn.ccmanager.ui.icons.Icons

/** Tab strip chip shown while the session runs a Code Mode script; a click cancels the script. */
@Composable
fun StopScriptChip(onStop: () -> Unit) =
    AppButton("Stop script", onStop, variant = ButtonVariant.SECONDARY, icon = Icons.Stop, compact = true, tooltip = "Stop the Code Mode script running in this session")
