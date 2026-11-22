package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.github.krpkbhdn.astrolabe.ui.web.browser.WebPageHost
import com.github.krpkbhdn.astrolabe.ui.web.input.ShortcutGuard
import com.github.krpkbhdn.astrolabe.ui.web.input.WebInputMapper
import com.github.krpkbhdn.astrolabe.ui.web.input.WebInputSink
import com.github.krpkbhdn.astrolabe.ui.web.input.keyModifierMask
import java.awt.Cursor
import kotlinx.coroutines.channels.Channel
import java.awt.event.MouseEvent

private const val PAN_NOTCH_DP = 60.0
private val log = org.slf4j.LoggerFactory.getLogger("WebCanvas")
private val keyLookupWarned = java.util.concurrent.atomic.AtomicBoolean()

@Composable
internal fun Modifier.inputHandling(
    host: WebPageHost,
    density: Float,
    viewport: PageViewport,
    focus: FocusRequester,
    cursor: Int,
    interaction: Boolean,
    blocked: Boolean,
    callbacks: CanvasCallbacks,
): Modifier {
    val mapper = remember(host) { WebInputMapper(host.asSink()) }
    val guard = remember { ShortcutGuard() }
    val hotKeys = remember { HotKeyGuard() }
    val locked = remember(host) { LockedPointer(host) }
    val currentViewport = rememberUpdatedState(viewport)
    val currentCallbacks = rememberUpdatedState(callbacks)
    val currentInteraction = rememberUpdatedState(interaction)
    val currentBlocked = rememberUpdatedState(blocked)
    val spaceDown = remember { java.util.concurrent.atomic.AtomicBoolean() }
    // A held button or hover state must not outlive the switch: CEF keeps :hover until it sees the pointer leave.
    LaunchedEffect(interaction, mapper) {
        if (!interaction) mapper.releaseAll()
    }
    LaunchedEffect(blocked, mapper) {
        if (blocked) mapper.releaseAll()
    }
    LaunchedEffect(interaction, locked) {
        if (!interaction) try { locked.run() } finally { locked.leave() }
    }
    return focusRequester(focus)
        .onFocusChanged {
            host.focus(it.isFocused)
            if (!it.isFocused) {
                spaceDown.set(false)
                hotKeys.reset()
            }
        }
        .onPreviewKeyEvent { event ->
            if (currentBlocked.value) return@onPreviewKeyEvent true
            if (event.key == Key.Spacebar) spaceDown.set(event.type == KeyEventType.KeyDown)
            val awt = awtEventOf(event)
            val c = currentCallbacks.value
            val outcome = awt?.let { hotKeys.onKey(it.id, it.keyCode, it.modifiersEx, c.hotKeyModes(), c.pageEditing()) }
            val action = outcome?.action
            when {
                action != null -> { c.onAction(action); true }
                outcome?.swallow == true -> true
                !currentInteraction.value -> false
                else -> forwardKey(host, guard, awt)
            }
        }
        .focusable()
        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
        // Keyed on stable values only: a restart mid-gesture would drop the pan and the held button.
        .pointerInput(mapper, density) {
            forwardPointer(mapper, locked, density, { currentViewport.value }, focus, { currentCallbacks.value }, currentInteraction::value, currentBlocked::value, spaceDown::get)
        }
}

internal class CanvasCallbacks(
    val onContextMenu: (Offset, Offset) -> Unit,
    val onPan: (Double, Double) -> Unit,
    val onZoom: (Offset, Int) -> Unit,
    val onPointer: (Offset, Offset) -> Unit,
    val onAction: (WebAction) -> Unit,
    val hotKeyModes: () -> HotKeyModes,
    val pageEditing: () -> Boolean,
    val canGrabFocus: () -> Boolean,
)

/**
 * Drives the inspector while real mouse input is not forwarded to the page: moves are conflated to one per frame and sent only
 * when the CSS point changes.
 */
private class LockedPointer(private val host: WebPageHost) {
    private val pending = java.util.concurrent.atomic.AtomicReference<IntOffset?>()
    private val sent = java.util.concurrent.atomic.AtomicReference<IntOffset?>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun move(x: Int, y: Int) {
        pending.set(IntOffset(x, y))
        wake.trySend(Unit)
    }

    fun click(x: Int, y: Int) = host.runScript(InspectorBridge.pointer("click", x.toDouble(), y.toDouble()))

    fun leave() {
        pending.set(null)
        if (sent.getAndSet(null) != null) host.runScript(InspectorBridge.pointer("leave", 0.0, 0.0))
    }

    fun scroll(dx: Double, dy: Double, x: Int, y: Int) = host.runScript(InspectorBridge.scrollBy(dx, dy, x.toDouble(), y.toDouble()))

    suspend fun run() {
        for (ignored in wake) {
            withFrameNanos {}
            val point = pending.getAndSet(null) ?: continue
            if (sent.getAndSet(point) != point) host.runScript(InspectorBridge.pointer("move", point.x.toDouble(), point.y.toDouble()))
        }
    }
}

private fun WebPageHost.asSink() = object : WebInputSink {
    override fun mouse(awtId: Int, xDip: Int, yDip: Int, button: Int, clickCount: Int, modifiers: Int) =
        this@asSink.mouse(awtId, xDip, yDip, button, clickCount, modifiers)

    override fun scrollBy(xDip: Int, yDip: Int, pixels: Int) = this@asSink.scrollBy(xDip, yDip, pixels)
}

private fun forwardKey(host: WebPageHost, guard: ShortcutGuard, awt: java.awt.event.KeyEvent?): Boolean {
    if (awt == null) {
        if (keyLookupWarned.compareAndSet(false, true)) log.warn("Cannot read the AWT key event behind a Compose key event; typing into the page will not work")
        return false
    }
    if (!guard.allows(awt)) return false
    host.key(awt)
    return true
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun PointerInputScope.forwardPointer(
    mapper: WebInputMapper,
    locked: LockedPointer,
    density: Float,
    viewport: () -> PageViewport,
    focus: FocusRequester,
    callbacks: () -> CanvasCallbacks,
    interaction: () -> Boolean,
    blocked: () -> Boolean,
    spaceDown: () -> Boolean,
) {
    awaitPointerEventScope {
        var secondarySwallowed = false
        var panning = false
        var pageHeld = 0
        var lastPan = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.first()
            if (blocked()) {
                pageHeld = 0
                panning = false
                event.changes.forEach { it.consume() }
                continue
            }
            val view = viewport()
            val c = callbacks()
            val live = interaction()
            if (!live) pageHeld = 0
            val canvasDp = Offset(change.position.x / density, change.position.y / density)
            val page = view.toPage(canvasDp)
            val x = page.x.toInt()
            val y = page.y.toInt()
            val inside = view.containsCanvas(canvasDp)
            val canPan = view.maxPanX > 0 || view.maxPanY > 0
            val shift = event.keyboardModifiers.isShiftPressed
            val keys = keyModifierMask(event.keyboardModifiers.isCtrlPressed, shift, event.keyboardModifiers.isAltPressed, event.keyboardModifiers.isMetaPressed)
            val secondary = event.button == PointerButton.Secondary
            val middle = event.button == PointerButton.Tertiary
            val spacePan = event.button == PointerButton.Primary && spaceDown()
            if (inside && event.type == PointerEventType.Enter && c.canGrabFocus()) focus.requestFocus()
            if (event.type == PointerEventType.Press) secondarySwallowed = false
            if (event.type == PointerEventType.Move && !event.changes.any { it.pressed }) pageHeld = 0
            when (event.type) {
                PointerEventType.Press -> if ((middle || spacePan) && inside && canPan) {
                    panning = true
                    lastPan = canvasDp
                } else if (inside) {
                    focus.requestFocus()
                    if (secondary && !shift) {
                        secondarySwallowed = true
                        c.onContextMenu(page, change.position)
                    } else if (live) {
                        pageHeld++
                        mapper.press(x, y, awtButton(event), keys, change.uptimeMillis)
                    } else if (event.button == PointerButton.Primary) locked.click(x, y)
                }
                PointerEventType.Release ->
                    if (panning && (middle || event.button == PointerButton.Primary)) panning = false
                    else if (secondary && secondarySwallowed) secondarySwallowed = false
                    else if (pageHeld > 0) {
                        pageHeld--
                        mapper.release(x, y, awtButton(event), keys)
                    }
                PointerEventType.Move -> {
                    if (inside) c.onPointer(page, change.position)
                    if (panning) {
                        c.onPan((lastPan.x - canvasDp.x).toDouble(), (lastPan.y - canvasDp.y).toDouble())
                        lastPan = canvasDp
                    } else if (!live) {
                        if (inside) locked.move(x, y) else locked.leave()
                    } else if (inside || pageHeld > 0) mapper.move(x, y, keys)
                }
                PointerEventType.Exit -> if (live) mapper.exit(x, y) else locked.leave()
                PointerEventType.Scroll ->
                    if (event.keyboardModifiers.isCtrlPressed) c.onZoom(canvasDp, if (change.scrollDelta.y < 0) 1 else -1)
                    else if (shift && inside && view.maxPanX > 0) c.onPan(change.scrollDelta.y * PAN_NOTCH_DP, change.scrollDelta.x * PAN_NOTCH_DP)
                    else if (inside && live) mapper.wheel(x, y, change.scrollDelta.y)
                    else if (inside) locked.scroll(change.scrollDelta.x * WebInputMapper.PIXELS_PER_NOTCH.toDouble(), change.scrollDelta.y * WebInputMapper.PIXELS_PER_NOTCH.toDouble(), x, y)
            }
            if (event.type == PointerEventType.Release && !event.changes.any { it.pressed }) pageHeld = 0
            change.consume()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun awtButton(event: PointerEvent): Int = when (event.button) {
    PointerButton.Secondary -> MouseEvent.BUTTON3
    PointerButton.Tertiary -> MouseEvent.BUTTON2
    else -> MouseEvent.BUTTON1
}

/**
 * In Compose 1.12 `nativeKeyEvent` is an internal wrapper whose `nativeEvent` is the real AWT event, read reflectively;
 * rebuilding the event breaks key identification on Windows.
 */
private fun awtEventOf(event: KeyEvent): java.awt.event.KeyEvent? = runCatching {
    val wrapper = event.nativeKeyEvent
    wrapper.javaClass.getMethod("getNativeEvent").invoke(wrapper) as? java.awt.event.KeyEvent
}.getOrNull()
