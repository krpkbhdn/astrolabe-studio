package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.taskattachment.TaskAttachmentProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.awt.image.BufferedImage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TaskImagesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `Alt+V and Ctrl+V or Cmd+V are told apart and other chords are ignored`() {
        assertThat(imagePasteOf(true, alt = true, ctrl = false, meta = false)).isEqualTo(ImagePaste.ALT_V)
        assertThat(imagePasteOf(true, alt = false, ctrl = true, meta = false)).isEqualTo(ImagePaste.CTRL_OR_CMD_V)
        assertThat(imagePasteOf(true, alt = false, ctrl = false, meta = true)).isEqualTo(ImagePaste.CTRL_OR_CMD_V)
        assertThat(imagePasteOf(true, alt = false, ctrl = false, meta = false)).isNull()
        assertThat(imagePasteOf(true, alt = true, ctrl = true, meta = false)).isNull()
        assertThat(imagePasteOf(false, alt = true, ctrl = false, meta = false)).isNull()
    }

    @Test
    fun `expired images are deleted and recent ones kept`() {
        val old = Files.createFile(dir.resolve("old.png"))
        val recent = Files.createFile(dir.resolve("recent.png"))
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(8))))

        TaskImages(dir, TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000)).deleteExpired()

        assertThat(old).doesNotExist()
        assertThat(recent).exists()
    }

    @Test
    fun `expiry boundary keeps a file exactly at retention and deletes one a second older`() {
        val file = Files.createFile(dir.resolve("edge.png"))
        val modified = Instant.parse("2026-01-01T00:00:00Z")
        Files.setLastModifiedTime(file, FileTime.from(modified))
        val images = TaskImages(dir, TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000))

        images.deleteExpired(modified.plus(Duration.ofDays(7)))
        assertThat(file).exists()
        images.deleteExpired(modified.plus(Duration.ofDays(7)).plusSeconds(1))
        assertThat(file).doesNotExist()
    }

    @Test
    fun `only png files are expired and a missing folder is fine`() {
        val other = Files.createFile(dir.resolve("note.txt"))
        Files.setLastModifiedTime(other, FileTime.from(Instant.EPOCH))

        TaskImages(dir, TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000)).deleteExpired()
        TaskImages(dir.resolve("missing"), TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000)).deleteExpired()

        assertThat(other).exists()
    }

    @Test
    fun `a clipboard image larger than the byte limit is rejected and nothing is stored`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val images = TaskImages(dir.resolve("store"), TaskAttachmentProperties(10, 1, Duration.ofDays(7), 10, 1000)) { image }
        assertThatThrownBy { images.saveFromClipboard() }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("larger")
        assertThat(dir.resolve("store")).doesNotExist()
        val stored = TaskImages(dir.resolve("store"), TaskAttachmentProperties(10, 1_000_000, Duration.ofDays(7), 10, 1000)) { image }.saveFromClipboard()
        assertThat(stored).isNotNull().exists().hasExtension("png")
    }

    @Test
    fun `a paste is long above ten lines or a thousand characters`() {
        val images = TaskImages(dir, TaskAttachmentProperties(10, 1024, Duration.ofDays(7), 10, 1000))

        assertThat(images.isLongPaste("a\n".repeat(10))).isFalse()
        assertThat(images.isLongPaste("a\n".repeat(11))).isTrue()
        assertThat(images.isLongPaste("x".repeat(1000))).isFalse()
        assertThat(images.isLongPaste("x".repeat(1001))).isTrue()
    }
}
