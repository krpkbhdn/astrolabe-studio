package com.github.krpkbhdn.astrolabe.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.github.krpkbhdn.astrolabe.ui.state.NotificationsState
import java.awt.Window
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalComposeUiApi::class)
class ReportingExceptionHandlersTest {
    private val notifications = NotificationsState({ "project" }) { true }
    private val window = mock(Window::class.java)
    private val fallbackErrors = mutableListOf<Throwable>()
    private val fallback = WindowExceptionHandlerFactory { WindowExceptionHandler { fallbackErrors += it } }
    private var remounts = 0

    private fun handler(recovery: WindowRecovery) =
        reportingExceptionHandlers(notifications, fallback, recovery) { remounts++ }.exceptionHandler(window)

    @Test
    fun `a recoverable error remounts the window and shows one toast per distinct message`() {
        var now = 0L
        val handler = handler(WindowRecovery(clock = { now }, minIntervalMillis = 10L))

        handler.onException(IllegalStateException("boom"))
        now += 10L
        handler.onException(IllegalStateException("boom"))

        assertThat(remounts).isEqualTo(2)
        assertThat(notifications.toasts).hasSize(1)
        assertThat(fallbackErrors).isEmpty()
    }

    @Test
    fun `an error the recovery refuses goes to the default handler without a remount`() {
        val recovery = WindowRecovery(clock = { 0L })
        recovery.tryRecover()

        val error = IllegalStateException("again")
        handler(recovery).onException(error)

        assertThat(fallbackErrors).containsExactly(error)
        assertThat(remounts).isZero()
        assertThat(notifications.popups).isEmpty()
    }
}
