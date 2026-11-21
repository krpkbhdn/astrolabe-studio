package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.geometry.Offset
import com.github.krpkbhdn.astrolabe.ui.web.browser.FakePageHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebViewGuardTest {
    private val view = WebView(1, FakePageHost())
    private val target = ElementTarget("div.card", "", "div", "", emptyList(), "", "", null, null, emptyMap(), null, null, null, null)

    @Test
    fun `no draft runs the action at once`() {
        var ran = false
        view.guardDraft { ran = true }
        assertThat(ran).isTrue()
    }

    @Test
    fun `an untouched draft is cancelled with its popover and the action runs`() {
        view.beginDraft(target)
        WebContextActions.showFeedback(view, Offset.Zero)
        var ran = false
        view.guardDraft { ran = true }
        assertThat(ran).isTrue()
        assertThat(view.picked).isNull()
        assertThat(view.contextMenu).isNull()
    }

    @Test
    fun `a changed draft holds the action and keeps the text`() {
        view.beginDraft(target)
        view.comment.setTextAndPlaceCursorAtEnd("typed")
        var ran = false
        view.guardDraft { ran = true }
        assertThat(ran).isFalse()
        assertThat(view.unsavedAction != null).isTrue()
        assertThat(view.comment.text.toString()).isEqualTo("typed")
    }

    @Test
    fun `the dialog's hotkeys switch plain page hotkeys off`() {
        view.beginDraft(target)
        view.comment.setTextAndPlaceCursorAtEnd("typed")
        view.guardDraft {}
        assertThat(view.hotKeyModes().menuOpen).isTrue()
    }
}
