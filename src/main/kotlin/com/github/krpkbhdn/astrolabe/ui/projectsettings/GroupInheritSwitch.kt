package com.github.krpkbhdn.astrolabe.ui.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.krpkbhdn.astrolabe.ui.components.AppSwitch
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.card
import com.github.krpkbhdn.astrolabe.ui.state.ConfirmRequest
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

/** Top of a group page: on = the page shows what the member projects have, off = the group's own editable copy. */
@Composable
fun GroupInheritSwitch(what: String, inherits: Boolean, busy: Boolean, confirm: (ConfirmRequest) -> Unit, onOverride: () -> Unit, onInherit: () -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AppSwitch(inherits, { on -> if (on) confirm(inheritConfirmOf(what, onInherit)) else onOverride() }, enabled = !busy)
            Label(INHERIT_TITLE, style = Type.bodyMedium)
        }
        Label(inheritNoteOf(inherits), color = colors.textTertiary, style = Type.caption, maxLines = Int.MAX_VALUE)
    }
}
