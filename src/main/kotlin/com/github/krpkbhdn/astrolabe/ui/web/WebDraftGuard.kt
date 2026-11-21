package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.ui.input.key.Key

/** The user's answer to the unsaved-draft dialog. */
enum class UnsavedChoice(val label: String, val hint: String) {
    SAVE("Save feedback", "Enter"),
    DISCARD("Discard changes", "D"),
    KEEP("Keep editing", "K"),
}

/** Pure decisions of the unsaved-draft guard: whether a draft differs from where it started, and which key answers the dialog. */
internal object DraftGuard {
    fun changed(comment: String, edits: Map<String, String>, baselineComment: String, baselineEdits: Map<String, String>): Boolean =
        comment.trim() != baselineComment.trim() || edits != baselineEdits

    private const val DISCARD_CHARS = "dDвВ"
    private const val KEEP_CHARS = "kKлЛ"

    /** Letters match by key or by the typed character (the Cyrillic letters on the same physical keys); Ctrl only goes with Enter, Alt and Meta with nothing. */
    fun choiceOf(key: Key, typed: Int = 0, ctrl: Boolean = false, altOrMeta: Boolean = false): UnsavedChoice? {
        if (altOrMeta) return null
        if (key == Key.Enter || key == Key.NumPadEnter) return UnsavedChoice.SAVE
        if (key == Key.Escape) return UnsavedChoice.KEEP
        if (ctrl) return null
        val char = typed.toChar()
        return when {
            key == Key.D || char in DISCARD_CHARS -> UnsavedChoice.DISCARD
            key == Key.K || char in KEEP_CHARS -> UnsavedChoice.KEEP
            else -> null
        }
    }

    /** Save needs something to save; the other answers are always possible. */
    fun runnable(choice: UnsavedChoice, canSave: Boolean): Boolean = choice != UnsavedChoice.SAVE || canSave
}
