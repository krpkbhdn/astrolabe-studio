package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.theme.LocalAppColors
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.web.browser.WebPageHost
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt

private const val RESIZE_DEBOUNCE_MILLIS = 50L

/** Draws the host's frame in physical pixels and forwards pointer, wheel and key input to it; a page larger than the canvas pans with the middle button, Space+drag or Shift+wheel and zooms with Ctrl+wheel. */
@Composable
fun WebCanvas(
    host: WebPageHost,
    viewport: PageViewport,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    device: DeviceSelection = DeviceSelection.Responsive,
    onPan: (dxDp: Double, dyDp: Double) -> Unit = { _, _ -> },
    onZoom: (cursorDp: Offset, notches: Int) -> Unit = { _, _ -> },
    onContextMenu: (pagePoint: Offset, canvasOffset: Offset) -> Unit = { _, _ -> },
    interaction: Boolean = true,
    blocked: Boolean = false,
    hotKeyModes: () -> HotKeyModes = { HotKeyModes() },
    pageEditing: () -> Boolean = { false },
    onAction: (WebAction) -> Unit = {},
    onPointer: (pagePoint: Offset, canvasOffset: Offset) -> Unit = { _, _ -> },
    canGrabFocus: () -> Boolean = { false },
    focusSignal: Boolean = false,
) {
    val callbacks = CanvasCallbacks(onContextMenu, onPan, onZoom, onPointer, onAction, hotKeyModes, pageEditing, canGrabFocus)
    val density = LocalDensity.current.density
    val frame by host.frame.collectAsState()
    val popup by host.popup.collectAsState()
    val cursor by host.cursor.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val colors = LocalAppColors.current
    val focus = remember { FocusRequester() }
    val renderScale = viewport.renderScale(density)
    val grab = rememberUpdatedState(canGrabFocus)

    // A tab that just opened, or a menu or popover that just closed, hands the keyboard back to the canvas.
    LaunchedEffect(focusSignal) {
        if (!interactive || !focusSignal) return@LaunchedEffect
        withFrameNanos {} // lets the closed popup release its focus before it is checked
        if (grab.value()) focus.requestFocus()
    }

    DisposableEffect(host) {
        host.setHidden(false)
        onDispose { host.setHidden(true) }
    }

    LaunchedEffect(host, size, viewport.widthCss, viewport.heightCss, renderScale) {
        if (size == IntSize.Zero) return@LaunchedEffect
        kotlinx.coroutines.delay(RESIZE_DEBOUNCE_MILLIS)
        host.resize(viewport.widthCss, viewport.heightCss, renderScale.toFloat())
    }

    // Frame pixels to displayed pixels; 1 only at 100% zoom with the pixel cap not reached.
    val display = density * viewport.scale / renderScale
    val originX = viewport.offsetX * density
    val originY = viewport.offsetY * density
    val quality = if (display == 1.0) FilterQuality.None else FilterQuality.Low
    Box(modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { size = it }.semantics { contentDescription = "Web page" }
                .then(if (interactive) Modifier.inputHandling(host, density, viewport, focus, cursor, interaction, blocked, callbacks) else Modifier),
        ) {
            drawRect(if (device.responsive) colors.surface else colors.background)
            if (!device.responsive) {
                drawRect(
                    colors.surface, Offset(originX.toFloat(), originY.toFloat()),
                    Size((viewport.widthCss * viewport.scale * density).toFloat(), (viewport.heightCss * viewport.scale * density).toFloat()),
                )
            }
            // A frame of the previous size can outlive a rotate or resize; it must never paint outside the page.
            val pageW = (viewport.widthCss * viewport.scale * density).toFloat()
            val pageH = (viewport.heightCss * viewport.scale * density).toFloat()
            clipRect(originX.toFloat(), originY.toFloat(), originX.toFloat() + pageW, originY.toFloat() + pageH) {
                frame?.let {
                    drawImage(
                        it.bitmap,
                        dstOffset = IntOffset(originX.toInt(), originY.toInt()),
                        dstSize = IntSize((it.widthPx * display).roundToInt(), (it.heightPx * display).roundToInt()),
                        filterQuality = quality,
                    )
                }
                popup?.let {
                    drawImage(
                        it.frame.bitmap,
                        dstOffset = IntOffset((originX + it.xDip * viewport.scale * density).toInt(), (originY + it.yDip * viewport.scale * density).toInt()),
                        dstSize = IntSize((it.frame.widthPx * display).roundToInt(), (it.frame.heightPx * display).roundToInt()),
                        filterQuality = quality,
                    )
                }
            }
        }
        if (!device.responsive) {
            Label(
                sizeLabel(viewport), Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                color = colors.textTertiary, style = Type.caption,
            )
        }
    }
}

internal fun sizeLabel(viewport: PageViewport): String = "${viewport.widthCss} × ${viewport.heightCss} · ${(viewport.scale * 100).roundToInt()}%"
