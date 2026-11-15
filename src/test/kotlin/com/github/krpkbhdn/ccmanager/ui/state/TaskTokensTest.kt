package com.github.krpkbhdn.ccmanager.ui.state

import androidx.compose.ui.text.TextRange
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TaskTokensTest {
    private val text = "see [Image #1] and [Pasted text #2 +12 lines] now"
    private val tokens = tokensIn(text)
    private val image = tokens[0].range
    private val pasted = tokens[1].range

    @Test
    fun `tokens are found with kind number and range`() {
        assertThat(tokens.map { it.kind to it.number }).containsExactly(TokenKind.IMAGE to 1, TokenKind.PASTED_TEXT to 2)
        assertThat(text.substring(image.start, image.end)).isEqualTo("[Image #1]")
        assertThat(tokensIn("[Image #x] [Pasted text #1 +a lines]")).isEmpty()
    }

    @Test
    fun `placeholder text builds the same tokens it is scanned as`() {
        assertThat(tokensIn(imageToken(7) + pastedTextToken(3, 40)).map { it.number }).containsExactly(7, 3)
        assertThat(lineCount("a\nb\nc\n")).isEqualTo(3)
    }

    @Test
    fun `backspace right after a token and delete right before it remove the whole token`() {
        assertThat(atomicDeletion(TextRange(image.end), tokens, backward = true)).isEqualTo(image)
        assertThat(atomicDeletion(TextRange(image.start), tokens, backward = false)).isEqualTo(image)
        assertThat(atomicDeletion(TextRange(image.end), tokens, backward = false)).isNull()
        assertThat(atomicDeletion(TextRange(image.end + 1), tokens, backward = true)).isNull()
        assertThat(atomicDeletion(TextRange(image.end, image.end + 2), tokens, backward = true)).isNull()
    }

    @Test
    fun `a caret inside a token goes to the edge it was moving towards, else the nearer one`() {
        val inside = image.start + 2
        assertThat(snapSelection(TextRange(inside), tokens, TextRange(image.end))).isEqualTo(TextRange(image.start))
        assertThat(snapSelection(TextRange(inside), tokens, TextRange(image.start))).isEqualTo(TextRange(image.end))
        assertThat(snapSelection(TextRange(inside), tokens)).isEqualTo(TextRange(image.start))
        assertThat(snapSelection(TextRange(image.end - 1), tokens)).isEqualTo(TextRange(image.end))
        assertThat(snapSelection(TextRange(image.end), tokens)).isEqualTo(TextRange(image.end))
    }

    @Test
    fun `a range that cuts a token grows to cover it and keeps its direction`() {
        assertThat(snapSelection(TextRange(image.start + 3, pasted.start + 4), tokens)).isEqualTo(TextRange(image.start, pasted.end))
        assertThat(snapSelection(TextRange(pasted.start + 4, image.start + 3), tokens)).isEqualTo(TextRange(pasted.end, image.start))
        assertThat(snapSelection(TextRange(0, 3), tokens)).isEqualTo(TextRange(0, 3))
    }

    @Test
    fun `pasted text placeholders expand in place and unknown ones stay`() {
        val pastedTexts = mapOf(2 to "line1\nline2")

        assertThat(expandPastedTexts(text, pastedTexts)).isEqualTo("see [Image #1] and line1\nline2 now")
        assertThat(expandPastedTexts(text, emptyMap())).isEqualTo(text)
        assertThat(expandPastedTexts("[Image #2]", pastedTexts)).isEqualTo("[Image #2]")
    }

    @Test
    fun `the worktree seed is the expanded text without image markers`() {
        assertThat(taskSeedText(text, mapOf(2 to "fix it"))).isEqualTo("see  and fix it now")
        assertThat(taskSeedText("[Image #1]", emptyMap())).isEmpty()
    }

    @Test
    fun `the prompt keeps markers in the text and lists only images still marked, paths with spaces intact`() {
        val spaced = Path.of("a dir", "x y.png")
        val images = mapOf(1 to spaced, 2 to Path.of("gone.png"), 3 to Path.of("c.png"))

        val prompt = taskPrompt("compare [Image #3] with [Image #1] please ", emptyMap(), images)

        assertThat(prompt).isEqualTo(
            "compare [Image #3] with [Image #1] please\n\nAttached images (read each with the Read tool):\n" +
                "[Image #3]: ${Path.of("c.png").toAbsolutePath()}\n[Image #1]: ${spaced.toAbsolutePath()}",
        )
    }

    @Test
    fun `a prompt of only markers and no images is just the text`() {
        assertThat(taskPrompt(" [Image #1] ", emptyMap(), emptyMap())).isEqualTo("[Image #1]")
        assertThat(taskPrompt("[Image #1]", emptyMap(), mapOf(1 to Path.of("c.png"))))
            .startsWith("[Image #1]\n\nAttached images").endsWith("[Image #1]: ${Path.of("c.png").toAbsolutePath()}")
    }

    @Test
    fun `two adjacent tokens are removed one per key and the caret between them is allowed`() {
        val two = "[Image #1][Image #2]"
        val both = tokensIn(two)
        val between = both[0].range.end

        assertThat(atomicDeletion(TextRange(two.length), both, backward = true)).isEqualTo(both[1].range)
        assertThat(atomicDeletion(TextRange(between), both, backward = true)).isEqualTo(both[0].range)
        assertThat(atomicDeletion(TextRange(between), both, backward = false)).isEqualTo(both[1].range)
        assertThat(snapSelection(TextRange(between), both)).isEqualTo(TextRange(between))
        assertThat(snapSelection(TextRange(0, between), both)).isEqualTo(TextRange(0, between))
    }

    @Test
    fun `a token at the very start and very end is removed by delete at 0 and backspace at the end`() {
        val only = "[Image #1]"
        val token = tokensIn(only)

        assertThat(atomicDeletion(TextRange(0), token, backward = false)).isEqualTo(TextRange(0, only.length))
        assertThat(atomicDeletion(TextRange(only.length), token, backward = true)).isEqualTo(TextRange(0, only.length))
        assertThat(atomicDeletion(TextRange(0), token, backward = true)).isNull()
        assertThat(atomicDeletion(TextRange(only.length), token, backward = false)).isNull()
    }

    @Test
    fun `a selection ending exactly on a token edge is not grown`() {
        assertThat(snapSelection(TextRange(0, image.start), tokens)).isEqualTo(TextRange(0, image.start))
        assertThat(snapSelection(TextRange(image.end, text.length), tokens)).isEqualTo(TextRange(image.end, text.length))
    }

    @Test
    fun `lines are counted the same for CRLF, LF and trailing newlines`() {
        assertThat(lineCount("a\r\nb\r\nc")).isEqualTo(3)
        assertThat(lineCount("a\r\nb\r\n")).isEqualTo(2)
        assertThat(lineCount((1..11).joinToString("\r\n"))).isEqualTo(11)
        assertThat(lineCount("one")).isEqualTo(1)
    }

    @Test
    fun `a pasted text that looks like a token is not expanded again and lists no image`() {
        val pastedTexts = mapOf(1 to "see [Pasted text #1 +3 lines] and [Image #1]")
        val images = mapOf(1 to Path.of("c.png"))

        assertThat(expandPastedTexts("[Pasted text #1 +12 lines]", pastedTexts)).isEqualTo(pastedTexts.getValue(1))
        assertThat(taskPrompt("[Pasted text #1 +12 lines]", pastedTexts, images)).isEqualTo(pastedTexts.getValue(1))
    }

    @Test
    fun `expanding works with text edited around the token and ignores its edited line count`() {
        val pastedTexts = mapOf(1 to "FULL")

        assertThat(expandPastedTexts("a[Pasted text #1 +12 lines]b", pastedTexts)).isEqualTo("aFULLb")
        assertThat(expandPastedTexts("[Pasted text #1 +5 lines][Pasted text #1 +12 lines]", pastedTexts)).isEqualTo("FULLFULL")
        assertThat(expandPastedTexts("[Pasted text #1 +12 line]", pastedTexts)).isEqualTo("[Pasted text #1 +12 line]")
    }

    @Test
    fun `a text of only tokens makes a prompt and only pasted text expands to its full text`() {
        assertThat(taskPrompt("[Pasted text #1 +12 lines]", mapOf(1 to "x\ny"), emptyMap())).isEqualTo("x\ny")
        assertThat(taskPrompt("[Image #1][Image #1]", emptyMap(), mapOf(1 to Path.of("c.png"))).lines().count { it.startsWith("[Image #1]:") }).isEqualTo(1)
    }
}
