package com.github.krpkbhdn.astrolabe.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.github.krpkbhdn.astrolabe.ui.await
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.locks.LockSupport
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities

private const val ESC = "\u001b"
private const val FOCUS_WAIT_SECONDS = 5L
private const val MILLIS_PER_SECOND = 1_000L
private const val RESIZE_POLL_NANOS = 1_000_000L

/**
 * Real AWT events dispatched straight to the component of an in-process Compose panel (no OS-level input), so the
 * conversion done by Compose itself is part of what is tested.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminalAwtKeyPipelineTest {
    private val connector = ScriptedConnector()
    private val session = TerminalSession(connector)
    private var frame: JFrame? = null

    @BeforeAll
    fun open() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val awtFocused = CountDownLatch(1)
        val listener = PropertyChangeListener { if (focusManager.focusOwner != null) awtFocused.countDown() }
        focusManager.addPropertyChangeListener("focusOwner", listener)
        SwingUtilities.invokeAndWait {
            frame = JFrame().apply {
                val panel = ComposePanel()
                panel.setContent { TerminalSessionView(session, Modifier.fillMaxSize(), TerminalTheme()) }
                contentPane.add(panel)
                setSize(640, 400)
                isVisible = true
            }
        }
        try {
            if (focusManager.focusOwner != null) awtFocused.countDown()
            awtFocused.await(FOCUS_WAIT_SECONDS, TimeUnit.SECONDS)
            runBlocking { withTimeoutOrNull(FOCUS_WAIT_SECONDS * MILLIS_PER_SECOND) { snapshotFlow { session.focused }.first { it } } }
            val resized = System.nanoTime() + TimeUnit.SECONDS.toNanos(FOCUS_WAIT_SECONDS)
            while (connector.resizes.isEmpty() && System.nanoTime() < resized) LockSupport.parkNanos(RESIZE_POLL_NANOS)
            assumeTrue(session.focused && connector.resizes.isNotEmpty()) { "the environment gave the window no focus or layout" }
        } finally {
            focusManager.removePropertyChangeListener("focusOwner", listener)
        }
    }

    @AfterAll
    fun close() {
        SwingUtilities.invokeAndWait { frame?.dispose() }
        session.close()
    }

    @BeforeEach
    fun resetShared() {
        settle(connector, session)
        connector.written.clear()
        session.clearSelection()
    }

    private fun dispatch(id: Int, code: Int, char: Char, modifiers: Int = 0) {
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: frame!!
        SwingUtilities.invokeAndWait { target.dispatchEvent(KeyEvent(target, id, System.nanoTime(), modifiers, code, char)) }
    }

    private fun typed(char: Char, modifiers: Int = 0) = dispatch(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, char, modifiers)

    private fun pressed(code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, modifiers: Int = 0) = dispatch(KeyEvent.KEY_PRESSED, code, char, modifiers)

    private fun written(): String {
        settle(connector, session)
        return connector.written.joinToString("") { String(it, Charsets.ISO_8859_1) }
    }

    /** The emulator hands bytes to the connector on its own thread, so wait for them instead of for a quiet screen. */
    private fun writtenAtLeast(expected: String): String {
        await { connector.writtenText().length >= expected.length }
        return written()
    }

    @Test
    fun layoutCharactersAndSpecialKeysBecomeTheRightBytes() {
        assumeFalse(!session.focused)
        typed('ї')
        typed('a')
        pressed(KeyEvent.VK_C, '\u0003', InputEvent.CTRL_DOWN_MASK)
        pressed(KeyEvent.VK_B, 'b', InputEvent.ALT_DOWN_MASK)
        typed('b', InputEvent.ALT_DOWN_MASK)
        pressed(KeyEvent.VK_P, 'p', InputEvent.ALT_DOWN_MASK)
        typed('p', InputEvent.ALT_DOWN_MASK)
        pressed(KeyEvent.VK_ENTER, '\n', InputEvent.SHIFT_DOWN_MASK)
        pressed(KeyEvent.VK_UP)
        pressed(KeyEvent.VK_TAB, '\t', InputEvent.SHIFT_DOWN_MASK)
        typed('@', InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        typed('\uD83D')
        typed('\uDE00')
        val expected = "Ñ\u0097a\u0003${ESC}b${ESC}p$ESC\r$ESC[A$ESC[Z@" + String("😀".toByteArray(), Charsets.ISO_8859_1)
        assertThat(writtenAtLeast(expected)).isEqualTo(expected)
    }

    private val clipboard by lazy { Toolkit.getDefaultToolkit().systemClipboard }
    private var savedClipboard: Transferable? = null

    @BeforeEach
    fun saveClipboard() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        savedClipboard =runCatching { clipboard.getContents(null) }.getOrNull()
    }

    @AfterEach
    fun restoreClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        savedClipboard?.let { runCatching { clipboard.setContents(it, null) } }
    }

    private fun selectHello() {
        connector.feed("\u001b[2J\u001b[Hhello")
        settle(connector, session)
        session.select(CellSelection(Cell(0, 0), Cell(4, 0)))
        assertThat(session.selection).describedAs("selection kept after the initial layout").isNotNull()
    }

    private fun ctrl(code: Int, char: Char, extra: Int = 0) = pressed(code, char, InputEvent.CTRL_DOWN_MASK or extra)

    private fun setClipboard(contents: Transferable) {
        val available = runCatching { clipboard.setContents(contents, null) }.isSuccess
        assumeTrue(available) { "the system clipboard is not accessible" }
    }

    private fun clipboardText() = clipboard.getData(DataFlavor.stringFlavor) as String

    @Test
    fun ctrlCWithASelectionCopiesItWithoutWritingTheInterrupt() {
        assumeFalse(!session.focused)
        selectHello()
        ctrl(KeyEvent.VK_C, '\u0003')
        assertThat(written()).isEmpty()
        assertThat(clipboardText()).isEqualTo("hello")
        assertThat(session.selection).isNull()
    }

    @Test
    fun aSecondCtrlCWritesTheInterrupt() {
        assumeFalse(!session.focused)
        selectHello()
        ctrl(KeyEvent.VK_C, '\u0003')
        ctrl(KeyEvent.VK_C, '\u0003')
        assertThat(writtenAtLeast("\u0003")).isEqualTo("\u0003")
    }

    @Test
    fun ctrlVPastesClipboardTextInsteadOfWritingTheControlByte() {
        assumeFalse(!session.focused)
        setClipboard(StringSelection("pasted"))
        ctrl(KeyEvent.VK_V, '\u0016')
        assertThat(writtenAtLeast("pasted")).isEqualTo("pasted")
    }

    @Test
    fun ctrlShiftCWithoutASelectionWritesNothing() {
        assumeFalse(!session.focused)
        ctrl(KeyEvent.VK_C, '\u0003', InputEvent.SHIFT_DOWN_MASK)
        assertThat(written()).isEmpty()
    }

    @Test
    fun ctrlVWithoutClipboardTextFallsThroughAsTheControlByte() {
        assumeFalse(!session.focused)
        setClipboard(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf<DataFlavor>()
            override fun isDataFlavorSupported(flavor: DataFlavor) = false
            override fun getTransferData(flavor: DataFlavor) = throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        })
        ctrl(KeyEvent.VK_V, '\u0016')
        assertThat(writtenAtLeast("\u0016")).isEqualTo("\u0016")
    }
}
