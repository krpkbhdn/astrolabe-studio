package com.github.krpkbhdn.astrolabe.ui.proposal

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.github.krpkbhdn.astrolabe.claude.proposal.Proposal
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalAnswer
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalService
import com.github.krpkbhdn.astrolabe.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.never

private const val FRAME_NANOS = 16_000_000L
private const val SETTLE_FRAMES = 6
private const val VERIFY_MILLIS = 15_000L

/** One proposal dialog in an offscreen Compose scene: real key events go in, answers reach a mocked [ProposalService]. */
@OptIn(InternalComposeUiApi::class)
internal class ProposalDialogHarness(private val proposal: Proposal, dialog: @Composable (ProposalContext, FrameTitle) -> Unit) : AutoCloseable {
    val service: ProposalService = mock(ProposalService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = ProposalState(service, scope, {})
    private val title = FrameTitle(com.github.krpkbhdn.astrolabe.ui.icons.Icons.Message, "Test", androidx.compose.ui.graphics.Color.Gray, emptyList(), null)
    private val scene = ImageComposeScene(1000, 800, Density(1f)) { AppTheme(dark = true) { dialog(ProposalContext(state, proposal), title) } }
    private var nanos = 0L

    init {
        settle()
    }

    fun settle() = repeat(SETTLE_FRAMES) { scene.render(nanos); nanos += FRAME_NANOS }

    fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift))
        settle()
    }


    fun answered(): ProposalAnswer {
        val captor = ArgumentCaptor.forClass(ProposalAnswer::class.java)
        verify(service, timeout(VERIFY_MILLIS)).answer(anyString(), captor.capture())
        return captor.value
    }

    fun assertNoAnswer() {
        runBlocking { scope.launch { withContext(Dispatchers.IO) { } }.join() }
        verify(service, never()).answer(anyString(), org.mockito.ArgumentMatchers.any(ProposalAnswer::class.java))
    }

    override fun close() {
        runCatching { scene.close() }
        scope.cancel()
    }
}
