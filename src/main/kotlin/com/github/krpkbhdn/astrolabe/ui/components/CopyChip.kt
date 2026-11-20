package com.github.krpkbhdn.astrolabe.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.IconSize
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

private const val CONFIRM_MILLIS = 1_400L
private const val PBCOPY_TIMEOUT_SECONDS = 3L

/** False when the system clipboard is busy (it throws on Windows) instead of crashing the window. */
fun copyToClipboard(text: String): Boolean =
    (isMac() && pbcopy(text)) ||
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess

private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

// The JBR pasteboard conversion garbles non-ASCII text on macOS; pbcopy takes raw UTF-8.
private fun pasteboardProcess(command: String, redirectErrors: Boolean): Process {
    val builder = ProcessBuilder(command).redirectErrorStream(redirectErrors)
    builder.environment()["LC_CTYPE"] = "UTF-8"
    builder.environment()["LANG"] = "en_US.UTF-8"
    return builder.start()
}

/** Blocking; the clipboard's plain text (pbpaste as UTF-8 on macOS, else AWT), or null when it holds none or cannot be read. */
fun readClipboardText(): String? =
    (if (isMac()) pbpaste()?.takeIf { it.isNotEmpty() } else null)
        ?: runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()

private fun pbpaste(): String? = runCatching {
    val process = pasteboardProcess("pbpaste", redirectErrors = false)
    val bytes = process.inputStream.use { it.readBytes() }
    if (process.waitFor(PBCOPY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
        String(bytes, Charsets.UTF_8)
    } else {
        process.destroyForcibly()
        null
    }
}.getOrNull()

private fun pbcopy(text: String): Boolean = runCatching {
    val process = pasteboardProcess("pbcopy", redirectErrors = true)
    process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    if (process.waitFor(PBCOPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.exitValue() == 0
    } else {
        process.destroyForcibly()
        false
    }
}.getOrDefault(false)

/**
 * Monospace chip showing [text]; clicking copies [copyText] and flips the icon to a check for a moment.
 * With [stretch] the chip fills the available width and the icon sits at its end.
 */
@Composable
fun CopyChip(
    text: String,
    modifier: Modifier = Modifier,
    copyText: String = text,
    style: TextStyle = Type.monoSmall,
    label: String = "Copy",
    stretch: Boolean = false,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CONFIRM_MILLIS)
            copied = false
        }
    }
    val shape = RoundedCornerShape(Radius.sm)
    WithTooltip(if (copied) "Copied" else label) {
        Pressable({ copyToClipboard(copyText); copied = true }, modifier.then(if (stretch) Modifier.fillMaxWidth() else Modifier).border(1.dp, colors.border, shape), shape) {
            Row(
                Modifier.then(if (stretch) Modifier.fillMaxWidth() else Modifier).padding(start = Spacing.sm + 2.dp, end = Spacing.sm - 1.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm - 1.dp),
            ) {
                Label(text, Modifier.weight(1f, fill = stretch), color = colors.textSecondary, style = style)
                Glyph(if (copied) Icons.Check else Icons.Copy, if (copied) colors.success else colors.textTertiary, size = IconSize.small)
            }
        }
    }
}
