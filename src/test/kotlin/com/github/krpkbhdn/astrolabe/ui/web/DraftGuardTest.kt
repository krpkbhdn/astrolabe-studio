package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.ui.input.key.Key
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DraftGuardTest {
    @Test
    fun `an untouched new draft is unchanged`() {
        assertThat(DraftGuard.changed("  ", emptyMap(), "", emptyMap())).isFalse()
    }

    @Test
    fun `typing into a new draft or editing a style is a change`() {
        assertThat(DraftGuard.changed("hi", emptyMap(), "", emptyMap())).isTrue()
        assertThat(DraftGuard.changed("", mapOf("color" to "red"), "", emptyMap())).isTrue()
    }

    @Test
    fun `an edit equal to the stored item is unchanged and any difference is a change`() {
        val stored = mapOf("color" to "red")
        assertThat(DraftGuard.changed("fix it", stored, "fix it", stored)).isFalse()
        assertThat(DraftGuard.changed("fix it!", stored, "fix it", stored)).isTrue()
        assertThat(DraftGuard.changed("fix it", mapOf("color" to "blue"), "fix it", stored)).isTrue()
        assertThat(DraftGuard.changed("fix it", emptyMap(), "fix it", stored)).isTrue()
    }

    @Test
    fun `keys map to the three answers`() {
        assertThat(DraftGuard.choiceOf(Key.Enter)).isEqualTo(UnsavedChoice.SAVE)
        assertThat(DraftGuard.choiceOf(Key.NumPadEnter)).isEqualTo(UnsavedChoice.SAVE)
        assertThat(DraftGuard.choiceOf(Key.D)).isEqualTo(UnsavedChoice.DISCARD)
        assertThat(DraftGuard.choiceOf(Key.K)).isEqualTo(UnsavedChoice.KEEP)
        assertThat(DraftGuard.choiceOf(Key.Escape)).isEqualTo(UnsavedChoice.KEEP)
        assertThat(DraftGuard.choiceOf(Key.X)).isNull()
    }

    @Test
    fun `cyrillic letters answer on non-latin layouts and modifiers block letters`() {
        assertThat(DraftGuard.choiceOf(Key.X, 0x432)).isEqualTo(UnsavedChoice.DISCARD)
        assertThat(DraftGuard.choiceOf(Key.X, 0x41b)).isEqualTo(UnsavedChoice.KEEP)
        assertThat(DraftGuard.choiceOf(Key.D, ctrl = true)).isNull()
        assertThat(DraftGuard.choiceOf(Key.D, altOrMeta = true)).isNull()
        assertThat(DraftGuard.choiceOf(Key.Enter, ctrl = true)).isEqualTo(UnsavedChoice.SAVE)
    }

    @Test
    fun `save is only possible when there is something to save`() {
        assertThat(DraftGuard.runnable(UnsavedChoice.SAVE, false)).isFalse()
        assertThat(DraftGuard.runnable(UnsavedChoice.SAVE, true)).isTrue()
        assertThat(DraftGuard.runnable(UnsavedChoice.DISCARD, false)).isTrue()
        assertThat(DraftGuard.runnable(UnsavedChoice.KEEP, false)).isTrue()
    }
}
