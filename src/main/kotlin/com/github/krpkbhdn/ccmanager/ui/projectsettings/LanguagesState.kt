package com.github.krpkbhdn.ccmanager.ui.projectsettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolRow
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolsService
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Language servers of one project: rows load on open and on [refresh] only, a toggle runs the plugin command off the UI thread; toggles are guarded per tool and run one at a time, because the plugin commands rewrite one settings file. */
class LanguagesState(
    private val projectTools: ProjectToolsService?,
    private val projectDir: Path,
    private val scope: CoroutineScope,
) {
    var rows by mutableStateOf<List<ProjectToolRow>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    private val working = mutableStateListOf<String>()
    private val toggleOrder = Mutex()
    val errors = mutableStateMapOf<String, String>()

    val busy: Boolean get() = working.isNotEmpty()

    fun isWorking(toolId: String): Boolean = toolId in working

    private var reloadRequested = false
    private var freshRequested = false

    /** The install already probed its tool fresh, so this reads the cache instead of re-probing every language server. */
    fun refreshAfterInstall() = refresh()

    /** Opening shows cached rows, [fresh] probes again. A refresh asked for while one runs is repeated afterwards, so the newest request always wins. */
    fun refresh(fresh: Boolean = false) {
        val service = projectTools ?: return
        freshRequested = freshRequested || fresh
        if (loading) {
            reloadRequested = true
            return
        }
        loading = true
        scope.launch {
            try {
                do {
                    reloadRequested = false
                    val probeAgain = freshRequested
                    freshRequested = false
                    rows = withContext(Dispatchers.IO) { if (probeAgain) service.rowsFresh(projectDir) else service.rows(projectDir) }
                    loadError = null
                } while (reloadRequested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    fun setEnabled(toolId: String, enabled: Boolean) {
        val service = projectTools ?: return
        if (toolId in working) return
        working += toolId
        errors.remove(toolId)
        scope.launch {
            try {
                toggleOrder.withLock {
                    withContext(Dispatchers.IO) {
                        if (enabled) service.enableLsp(projectDir, toolId) else service.disableLsp(projectDir, toolId)
                    }
                }
                rows = rows.map { if (it.tool.id() == toolId) ProjectToolRow(it.tool, it.status, it.detected(), enabled, it.userLevelEnabled()) else it }
                if (loading) reloadRequested = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[toolId] = e.message ?: e.javaClass.simpleName
            } finally {
                working -= toolId
            }
        }
    }
}
