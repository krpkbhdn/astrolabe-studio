package com.github.krpkbhdn.astrolabe.ui.review

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

private const val THUMBNAIL_MAX_SIDE = 256f

internal fun decode(path: Path): ImageBitmap = Image.makeFromEncoded(Files.readAllBytes(path)).use { it.toComposeImageBitmap() }

internal fun decodeThumbnail(path: Path): ImageBitmap {
    val full = Image.makeFromEncoded(Files.readAllBytes(path))
    try {
        val scale = min(1f, THUMBNAIL_MAX_SIDE / max(full.width, full.height))
        val width = max(1, (full.width * scale).roundToInt())
        val height = max(1, (full.height * scale).roundToInt())
        Surface.makeRasterN32Premul(width, height).use { surface ->
            val source = Rect.makeWH(full.width.toFloat(), full.height.toFloat())
            surface.canvas.drawImageRect(full, source, Rect.makeWH(width.toFloat(), height.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
            return surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    } finally {
        full.close()
    }
}
