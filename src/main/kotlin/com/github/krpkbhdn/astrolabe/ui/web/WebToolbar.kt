package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.review.ReviewStatus
import com.github.krpkbhdn.astrolabe.ui.components.AppButton
import com.github.krpkbhdn.astrolabe.ui.components.ButtonVariant
import com.github.krpkbhdn.astrolabe.ui.components.AppTextField
import com.github.krpkbhdn.astrolabe.ui.components.IconButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.components.VerticalRule
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors

private val fieldHeight = 30.dp
private val addressMinWidth = 120.dp
private val dividerHeight = 18.dp

private const val ICON_WIDTH = 28f
private const val GAP = 4f
private const val DIVIDER_WIDTH = 17f
private const val PADDING = 24f
private const val ADDRESS_WIDTH = 132f
private const val NAV_WIDTH = 3 * ICON_WIDTH + 3 * GAP
private const val LOCKED_LABEL_WIDTH = 60f
private const val DELIVERY_WIDTH = 110f
private const val OVERLAYS_FULL_WIDTH = DIVIDER_WIDTH + 7 * ICON_WIDTH + 6 * 2f + GAP
private const val OVERLAYS_COMPACT_WIDTH = DIVIDER_WIDTH + ICON_WIDTH + GAP
private const val CHIP_BASE = 30f
private const val CHAR_WIDTH = 7f
private const val CAPTURING_WIDTH = 90f
private const val ZOOM_CHIP_WIDTH = 60f
private const val PRESET_SIZE_WIDTH = 80f
private const val CUSTOM_SIZE_WIDTH = 160f
private const val SCOPE_WIDTH = 50f

/** Estimated width of the controls that never group; the overlays are measured by [WebToolbarLayout]. */
private fun fixedWidth(view: WebView, targetChip: String): Float {
    val d = view.device
    var device = DIVIDER_WIDTH + CHIP_BASE + d.presetLabel().length * CHAR_WIDTH + GAP
    if (d.scope != null) device += SCOPE_WIDTH
    if (!d.responsive) device += ZOOM_CHIP_WIDTH + ICON_WIDTH + GAP + if (d.preset != null) PRESET_SIZE_WIDTH else CUSTOM_SIZE_WIDTH
    val pickLock = DIVIDER_WIDTH + 2 * ICON_WIDTH + GAP + if (view.interaction) 0f else LOCKED_LABEL_WIDTH
    val screenshot = DIVIDER_WIDTH + ICON_WIDTH + GAP + if (view.shooting) CAPTURING_WIDTH else 0f
    val target = CHIP_BASE + targetChip.length * CHAR_WIDTH + GAP
    val consoleAndHistory = DIVIDER_WIDTH + 2 * (ICON_WIDTH + GAP)
    return PADDING + NAV_WIDTH + ADDRESS_WIDTH + device + pickLock + screenshot + target + consoleAndHistory + DELIVERY_WIDTH
}

/** Thin divider between logical groups of the toolbar. */
@Composable
private fun GroupDivider() = VerticalRule(Modifier.padding(horizontal = Spacing.xs).height(dividerHeight))

/** Groups separated by dividers: navigation and address | device and zoom | pick and lock | overlays | screenshot | console. Each switch is pushed into the page through [WebState]. */
@Composable
fun WebToolbar(view: WebView, state: WebState, modifier: Modifier = Modifier) {
    val page by view.host.page.collectAsState()
    val toggles = view.toggles
    val window = LocalWindowInfo.current
    val chipText = targetChipText(state.captureTargets(view.projectId), state.pinnedReview(view.projectId), state.pinIsStale(view.projectId))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val overlays = GroupableItem(ToolbarItemId.OVERLAYS, OVERLAYS_FULL_WIDTH, OVERLAYS_COMPACT_WIDTH)
        val compactOverlays = ToolbarItemId.OVERLAYS in WebToolbarLayout.compacted(maxWidth.value, fixedWidth(view, chipText), listOf(overlays))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            IconButton(Icons.ChevronLeft, view.host::back, tooltip = "Back", enabled = page.canGoBack)
            IconButton(Icons.ChevronRight, view.host::forward, tooltip = "Forward", enabled = page.canGoForward)
            if (page.loading) IconButton(Icons.Stop, view.host::stop, tooltip = "Stop") else IconButton(Icons.Refresh, view.host::reload, tooltip = "Reload")
            AppTextField(
                view.address, Modifier.widthIn(min = addressMinWidth).weight(1f).padding(horizontal = Spacing.xs),
                placeholder = "http://localhost:3000", icon = Icons.Globe, mono = true,
                onSubmit = { state.go(view, view.address.text.toString()) }, height = fieldHeight,
            )
            GroupDivider()
            WebDeviceControls(view.device) { view.device = it }
            GroupDivider()
            IconButton(
                Icons.MousePointer, { state.perform(view, if (window.keyboardModifiers.isShiftPressed) WebAction.StickyPick else WebAction.TogglePick) },
                tooltip = "Pick element (I) - Shift+click or Shift+I keeps picking", active = toggles.pick,
            )
            IconButton(
                Icons.Hand, { state.perform(view, WebAction.ToggleLock) },
                tooltip = "Lock page (L) - the page ignores the mouse, the inspector still works", active = !view.interaction,
            )
            if (!view.interaction) Label("Locked", color = colors.accent, style = Type.caption)
            GroupDivider()
            WebOverlayMenu(toggles, compact = compactOverlays) { state.setToggles(view, it) }
            GroupDivider()
            WebScreenshotMenu(view, state, enabled = state.canShoot(view))
            WebReviewTargetMenu(view.projectId, state)
            if (view.shooting) Label("Capturing...", color = colors.accent, style = Type.caption)
            GroupDivider()
            IconButton(Icons.Terminal, { view.consoleOpen = !view.consoleOpen }, tooltip = "Console", active = view.consoleOpen)
            WebHistoryMenu(state.history, state::chooseHistory)
            ReviewDeliveryButton(view.projectId, state)
        }
    }
}

@Composable
private fun ReviewDeliveryButton(projectId: Long, state: WebState) {
    val revisions = state.revisions(projectId)
    val pending = revisions.firstOrNull { it.status() == ReviewStatus.PENDING }
    if (pending != null) {
        val open = state.pendingCount(projectId)
        val sending = state.isSending(pending.id())
        AppButton(
            if (open > 0) "Send ($open)" else "Send", { state.send(pending) }, variant = ButtonVariant.PRIMARY, icon = Icons.Send,
            enabled = open > 0 && !sending, loading = sending, compact = true,
        )
        return
    }
    revisions.firstOrNull { it.status() == ReviewStatus.SENT }?.let { AppButton("Approve", { state.approve(it) }, icon = Icons.Check, compact = true) }
}
