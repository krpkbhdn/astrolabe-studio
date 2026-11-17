package com.github.krpkbhdn.ccmanager.ui.projectsettings

import androidx.compose.runtime.mutableStateMapOf
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.toolchain.DetectedTechnology
import com.github.krpkbhdn.ccmanager.toolchain.LspHealthMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Language servers detected per project for the status bar indicator: the monitor pushes every state change into the map, so nothing blocks the UI thread and the old value stays until a new one arrives. */
class DetectedTechState(
    private val health: LspHealthMonitor?,
    private val scope: CoroutineScope,
) {
    private val byProject = mutableStateMapOf<Long, List<DetectedTechnology>>()
    private var unsubscribe: Runnable? = null
    private var trackedId: Long? = null

    fun of(projectId: Long): List<DetectedTechnology> = byProject[projectId].orEmpty()

    /** Watches [project] (and only it) from now on and probes it at once. */
    @Synchronized
    fun track(project: Project) {
        stop()
        val monitor = health ?: return
        if (project.group) return
        trackedId = project.id
        unsubscribe = monitor.watch(project.path) { byProject[project.id] = it }
        refresh(project)
    }

    /** Stops only when [id] is still the tracked project: a leaving screen must not cancel the watch of the one that replaced it. */
    @Synchronized
    fun stopIfTracking(id: Long) {
        if (trackedId == id) stop()
    }

    private fun stop() {
        unsubscribe?.run()
        unsubscribe = null
        trackedId = null
    }

    /** Probes now; the result arrives through the watch of [project], so a project that is not tracked is not updated. */
    fun refresh(project: Project) {
        val monitor = health ?: return
        if (project.group) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { monitor.checkNow(project.path) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the indicator stays as it was; detection is a hint, not worth an error toast
            }
        }
    }
}
