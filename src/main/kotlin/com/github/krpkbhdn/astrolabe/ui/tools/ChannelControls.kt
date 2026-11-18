package com.github.krpkbhdn.astrolabe.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.ToolStatus
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.MenuEntry
import com.github.krpkbhdn.astrolabe.ui.components.PopupMenu
import com.github.krpkbhdn.astrolabe.ui.icons.Icons

/** Version channel dropdown for a tool with a pinned verified version, plus "Use verified version" when the newest one failed. */
@Composable
fun ChannelControls(tool: ToolDefinition, status: ToolStatus?, tools: ToolsState) {
    if (!showsChannelPicker(tool)) return
    val selected = tools.selectedChannel(tool.id)
    var open by remember { mutableStateOf(false) }
    val busy = tools.installRunning
    Box {
        AppButton(channelItemOf(selected).label, { open = !open }, variant = ButtonVariant.SECONDARY, icon = Icons.ChevronDown, compact = true, enabled = !busy)
        if (open) {
            val entries = CHANNEL_ITEMS.map { item ->
                MenuEntry(item.label, hint = item.detail, checked = item.channel == selected, onSelect = { tools.selectChannel(tool.id, item.channel) })
            }
            PopupMenu(entries, { open = false }, alignEnd = true)
        }
    }
    if (offersVerifiedVersion(tool, tools.installedChannel(tool.id), status)) {
        AppButton("Use verified version", { tools.useVerifiedVersion(tool.id) }, variant = ButtonVariant.PRIMARY, compact = true, enabled = !busy)
    }
}
