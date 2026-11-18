package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.ui.text.TextRange
import java.nio.file.Path

enum class TokenKind { IMAGE, PASTED_TEXT }

/** A placeholder in the task text: `[Image #N]` or `[Pasted text #N +M lines]`, at [range]. */
data class TaskToken(val kind: TokenKind, val number: Int, val range: TextRange)

private val tokenPattern = Regex("""\[Image #(\d+)]|\[Pasted text #(\d+) \+\d+ lines]""")
private val imageTokenPattern = Regex("""\[Image #\d+]""")
private const val IMAGE_BLOCK_TITLE = "Attached images (read each with the Read tool):"

fun imageToken(number: Int) = "[Image #$number]"

fun pastedTextToken(number: Int, lines: Int) = "[Pasted text #$number +$lines lines]"

fun lineCount(text: String) = text.trimEnd('\n', '\r').lines().size

/** Every placeholder present in [text], in order. */
fun tokensIn(text: String): List<TaskToken> = tokenPattern.findAll(text).map { match ->
    val image = match.groups[1]?.value
    if (image != null) TaskToken(TokenKind.IMAGE, image.toInt(), TextRange(match.range.first, match.range.last + 1))
    else TaskToken(TokenKind.PASTED_TEXT, match.groups[2]!!.value.toInt(), TextRange(match.range.first, match.range.last + 1))
}.toList()

private fun TextRange.strictlyInside(offset: Int) = offset > start && offset < end

/**
 * Moves a selection off the inside of tokens: a range grows to cover the tokens it touches, a caret goes to the edge it was moving towards
 * (judged from [previous]) or else to the nearer edge.
 */
fun snapSelection(selection: TextRange, tokens: List<TaskToken>, previous: TextRange? = null): TextRange {
    if (tokens.isEmpty()) return selection
    if (selection.collapsed) return TextRange(snapCaret(selection.start, tokens, previous?.takeIf { it.collapsed }?.start))
    val low = tokens.firstOrNull { it.range.strictlyInside(selection.min) }?.range?.start ?: selection.min
    val high = tokens.firstOrNull { it.range.strictlyInside(selection.max) }?.range?.end ?: selection.max
    return if (selection.reversed) TextRange(high, low) else TextRange(low, high)
}

private fun snapCaret(caret: Int, tokens: List<TaskToken>, previous: Int?): Int {
    val range = tokens.firstOrNull { it.range.strictlyInside(caret) }?.range ?: return caret
    return when {
        previous != null && previous >= range.end -> range.start
        previous != null && previous <= range.start -> range.end
        caret - range.start < range.end - caret -> range.start
        else -> range.end
    }
}

/** The token a Backspace (before the caret) or Delete (after it) removes whole, or null when the key edits ordinary text. */
fun atomicDeletion(selection: TextRange, tokens: List<TaskToken>, backward: Boolean): TextRange? {
    if (!selection.collapsed) return null
    return tokens.firstOrNull { if (backward) it.range.end == selection.start else it.range.start == selection.start }?.range
}

/** [text] with every pasted-text placeholder whose number is in [pastedTexts] replaced by the full pasted text. */
fun expandPastedTexts(text: String, pastedTexts: Map<Int, String>): String {
    val out = StringBuilder()
    var last = 0
    for (token in tokensIn(text)) {
        val full = pastedTexts[token.number]?.takeIf { token.kind == TokenKind.PASTED_TEXT } ?: continue
        out.append(text, last, token.range.start).append(full)
        last = token.range.end
    }
    return out.append(text, last, text.length).toString()
}

/** The text with image placeholders cut out and pasted text expanded, trimmed: what names a task worktree. */
fun taskSeedText(text: String, pastedTexts: Map<Int, String>): String = expandPastedTexts(text, pastedTexts).replace(imageTokenPattern, "").trim()

/** Expanded task text, then one line per attached image that is still marked in the text, so each marker maps to its file. */
fun taskPrompt(text: String, pastedTexts: Map<Int, String>, images: Map<Int, Path>): String {
    val expanded = expandPastedTexts(text, pastedTexts).trim()
    val marked = tokensIn(text).filter { it.kind == TokenKind.IMAGE }.map { it.number }.distinct().filter { it in images }
    if (marked.isEmpty()) return expanded
    val lines = marked.joinToString("\n") { "${imageToken(it)}: ${images.getValue(it).toAbsolutePath()}" }
    val block = "$IMAGE_BLOCK_TITLE\n$lines"
    return if (expanded.isEmpty()) block else "$expanded\n\n$block"
}
