package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.taskattachment.TaskAttachmentProperties
import java.awt.Image
import com.github.krpkbhdn.astrolabe.ui.components.readClipboardText
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

private const val BYTES_PER_MB = 1024 * 1024

/** PNG files of images pasted into the Start Task dialog, kept under [dir] so the started session can read them by path. */
class TaskImages(
    private val dir: Path,
    private val limits: TaskAttachmentProperties,
    private val clipboardImage: () -> Image? = { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.imageFlavor) as? Image },
) {
    val maxCount: Int get() = limits.maxImages()

    /** True when the clipboard holds an image and no text; reads no data, so it is cheap enough for a key handler. */
    fun clipboardHasOnlyImage(): Boolean = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) && !clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    }.getOrDefault(false)

    /** Blocking; stores the clipboard image as a new PNG, or returns null when the clipboard holds none or cannot be read. Throws when the PNG exceeds the size limit. */
    fun saveFromClipboard(): Path? {
        val image = runCatching { clipboardImage() }.getOrNull() ?: return null
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        bitmap.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val png = ByteArrayOutputStream().also { if (!ImageIO.write(bitmap, "png", it)) return null }.toByteArray()
        check(png.size <= limits.maxImageBytes()) { "The image is larger than ${limits.maxImageBytes() / BYTES_PER_MB} MB" }
        Files.createDirectories(dir)
        return Files.write(dir.resolve("${UUID.randomUUID()}.png"), png)
    }

    /** Blocking; the clipboard's plain text, or null when it holds none. */
    fun clipboardText(): String? = readClipboardText()

    fun isLongPaste(text: String) = text.length > limits.longPasteChars() || lineCount(text) > limits.longPasteLines()

    fun delete(files: Collection<Path>) = files.forEach { runCatching { Files.deleteIfExists(it) } }

    fun deleteExpired(now: Instant = Instant.now()) {
        if (!Files.isDirectory(dir)) return
        Files.newDirectoryStream(dir, "*.png").use { stream ->
            stream.filter { Files.getLastModifiedTime(it).toInstant().plus(limits.retention()).isBefore(now) }.let(::delete)
        }
    }
}

/** Which paste chord was pressed: Alt+V always asks for the clipboard image, Ctrl+V and Cmd+V only take over for an image-only clipboard or a long text. */
enum class ImagePaste { ALT_V, CTRL_OR_CMD_V }

fun imagePasteOf(isV: Boolean, alt: Boolean, ctrl: Boolean, meta: Boolean): ImagePaste? = when {
    !isV -> null
    alt && !ctrl && !meta -> ImagePaste.ALT_V
    (ctrl || meta) && !alt -> ImagePaste.CTRL_OR_CMD_V
    else -> null
}
