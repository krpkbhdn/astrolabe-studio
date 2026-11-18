package com.github.krpkbhdn.astrolabe.ui.teams

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.krpkbhdn.astrolabe.ui.components.IconButton
import com.github.krpkbhdn.astrolabe.ui.components.Label
import com.github.krpkbhdn.astrolabe.ui.icons.Icons
import com.github.krpkbhdn.astrolabe.ui.state.ImagePaste
import com.github.krpkbhdn.astrolabe.ui.state.TaskDialogState
import com.github.krpkbhdn.astrolabe.ui.state.imagePasteOf
import com.github.krpkbhdn.astrolabe.ui.theme.Radius
import com.github.krpkbhdn.astrolabe.ui.theme.Spacing
import com.github.krpkbhdn.astrolabe.ui.theme.Type
import com.github.krpkbhdn.astrolabe.ui.theme.colors
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbnailSize = 40.dp

/**
 * Key handling of the task field before the text field sees it: Alt+V pastes the clipboard image, Ctrl+V and Cmd+V take over only for an
 * image-only clipboard or a long text, and Backspace or Delete next to a placeholder removes it whole.
 */
fun taskFieldKey(event: KeyEvent, tasks: TaskDialogState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val plain = !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed
    return when {
        plain && event.key == Key.Backspace -> tasks.deleteTokenAtCaret(backward = true)
        plain && event.key == Key.Delete -> tasks.deleteTokenAtCaret(backward = false)
        else -> when (imagePasteOf(event.key == Key.V, event.isAltPressed, event.isCtrlPressed, event.isMetaPressed)) {
            ImagePaste.ALT_V -> { tasks.pasteImage(onlyIfPresent = false); true }
            ImagePaste.CTRL_OR_CMD_V -> if (tasks.clipboardHasOnlyImage()) { tasks.pasteImage(onlyIfPresent = true); true } else tasks.pasteLongText()
            null -> false
        }
    }
}

/** Thumbnails of the pasted images, each labelled with the number of its `[Image #N]` placeholder. */
@Composable
fun TaskImageRow(tasks: TaskDialogState) {
    if (tasks.images.isEmpty() && tasks.imageHint == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        tasks.images.entries.sortedBy { it.key }.forEach { (number, file) ->
            val bitmap by produceState<ImageBitmap?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(file)).use { it.toComposeImageBitmap() } }.getOrNull() } }
            bitmap?.let { Image(it, null, Modifier.size(thumbnailSize).clip(RoundedCornerShape(Radius.sm)), contentScale = ContentScale.Crop) }
            Label("Image #$number", color = colors.textSecondary, style = Type.caption)
            IconButton(Icons.Close, { tasks.removeImage(number) }, tint = colors.textTertiary, size = 20.dp, tooltip = "Remove image")
        }
        tasks.imageHint?.let { Label(it, color = colors.textTertiary, style = Type.caption) }
    }
}
