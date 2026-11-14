package com.github.krpkbhdn.ccmanager.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationSettingsService
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ContainerRuntimeDetector
import com.github.krpkbhdn.ccmanager.ui.state.io
import com.github.krpkbhdn.ccmanager.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Spring beans behind the Code Mode settings page. */
class CodeModeBackend(val isolation: IsolationSettingsService, val runtimes: ContainerRuntimeDetector, val image: String)

/** Selected isolation backend plus detection and image pull of the container runtimes; all blocking work runs on IO. */
class CodeModeSettingsState(
    private val backend: CodeModeBackend?,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit = {},
) {
    var selected by mutableStateOf(backend?.isolation?.get() ?: IsolationBackend.GRAALJS)
        private set
    var pulling by mutableStateOf<IsolationBackend?>(null)
        private set
    var pullFailure by mutableStateOf<String?>(null)
        private set
    var pullDone by mutableStateOf<IsolationBackend?>(null)
        private set
    val statuses: SnapshotStateMap<IsolationBackend, RuntimeStatus> = mutableStateMapOf()
    val available: Boolean get() = backend != null
    val image: String get() = backend?.image.orEmpty()

    init {
        backend?.isolation?.subscribe { scope.launch { selected = io { backend.isolation.get() } } }
    }

    fun select(choice: IsolationBackend) {
        val service = backend?.isolation ?: return
        scope.launch {
            runGuarded(reportError) { io { service.set(choice) } }
            selected = io { service.get() }
        }
        if (IsolationView.isContainer(choice) && choice !in statuses) refresh(choice)
    }

    fun refreshAll() = IsolationBackend.entries.filter(IsolationView::isContainer).forEach(::refresh)

    fun refresh(runtime: IsolationBackend) {
        val detector = backend?.runtimes ?: return
        statuses.remove(runtime)
        scope.launch {
            val status = io {
                val availability = detector.availability(runtime)
                RuntimeStatus(availability.available, availability.reason, if (availability.available) detector.imagePresent(runtime, image) else null)
            }
            statuses[runtime] = status
        }
    }

    fun pullImage(runtime: IsolationBackend) {
        val detector = backend?.runtimes ?: return
        if (pulling != null) return
        pulling = runtime
        pullFailure = null
        pullDone = null
        scope.launch {
            val failure = io { detector.pullImage(runtime, image) }
            pulling = null
            if (failure.isEmpty()) pullDone = runtime else pullFailure = failure
            refresh(runtime)
        }
    }
}
