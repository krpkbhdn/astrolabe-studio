package com.github.krpkbhdn.ccmanager.ui.presets.apply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.ccmanager.preset.ProjectPreset
import com.github.krpkbhdn.ccmanager.ui.components.AppButton
import com.github.krpkbhdn.ccmanager.ui.components.ButtonVariant
import com.github.krpkbhdn.ccmanager.ui.components.DialogBody
import com.github.krpkbhdn.ccmanager.ui.components.Label
import com.github.krpkbhdn.ccmanager.ui.components.ModalHost
import com.github.krpkbhdn.ccmanager.ui.icons.Icons
import com.github.krpkbhdn.ccmanager.ui.theme.Spacing
import com.github.krpkbhdn.ccmanager.ui.theme.Type
import com.github.krpkbhdn.ccmanager.ui.theme.colors

private val offerWidth = 540.dp
private const val NAME_LIMIT = 32

/** "Set up <project>?": the recommended presets, then Review & apply, Not for this project or Later. */
@Composable
fun PresetOfferDialog(offer: PresetOffer, presets: List<ProjectPreset>, state: PresetOfferState) {
    ModalHost(state::later, width = offerWidth) {
        DialogBody("Set up ${offer.project.name.take(NAME_LIMIT)}?", Icons.Sparkles, colors.accent, actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                AppButton("Not for this project", state::decline, variant = ButtonVariant.GHOST)
                AppButton("Later", state::later)
                AppButton("Review & apply", state::reviewAndApply, variant = ButtonVariant.PRIMARY)
            }
        }) {
            Label("This project has no Claude setup from a preset yet. Presets add instructions, skills, plugins and settings to it. You review every change, and can pick other presets, before anything is written.", color = colors.textSecondary, maxLines = 4)
            Label("Recommended: " + presets.filter { it.id() in offer.presetIds }.joinToString { it.name() }, style = Type.bodyMedium, maxLines = 2, tooltipWhenTruncated = true)
        }
    }
}
