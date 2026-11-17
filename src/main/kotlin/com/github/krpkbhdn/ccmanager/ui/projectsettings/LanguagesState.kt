package com.github.krpkbhdn.ccmanager.ui.projectsettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.toolchain.GroupLspService
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

/** Language servers of one project or group (a group that inherits shows its members' languages read-only until [inheritFromMembers]): rows load on open and on [refresh] only, a toggle runs the plugin command off the UI thread; toggles are guarded per tool and run one at a time, because the plugin commands rewrite one settings file. */
class LanguagesState(
    private val projectTools: ProjectToolsService?,
    private val projectDir: Path,
    private val scope: CoroutineScope,
    private val groupLsp: GroupLspService? = null,
    private val groupId: Long? = null,
    private val memberFolders: () -> List<Pair<String, Path>> = { emptyList() },
) {
    var rows by mutableStateOf<List<ProjectToolRow>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    /** A group without its own LSP plugin set: the page is read-only. */
    var inherits by mutableStateOf(false)
        private set

    /** Member names per tool id while [inherits], for "From ..." notes. */
    var sources by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    private var switching by mutableStateOf(false)
    private val working = mutableStateListOf<String>()
    private val toggleOrder = Mutex()
    val errors = mutableStateMapOf<String, String>()

    val busy: Boolean get() = working.isNotEmpty() || switching

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
                    val loaded = withContext(Dispatchers.IO) { load(service, probeAgain) }
                    rows = loaded.rows
                    inherits = loaded.inherits
                    sources = loaded.sources
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

    private class Loaded(val rows: List<ProjectToolRow>, val inherits: Boolean, val sources: Map<String, List<String>>)

    private fun load(service: ProjectToolsService, fresh: Boolean): Loaded {
        val rows = if (fresh) service.rowsFresh(projectDir) else service.rows(projectDir)
        val inheriting = groupId != null && groupLsp?.inherits(groupId) == true
        val from = if (inheriting) sourcesOf(memberFolders().map { (name, dir) -> name to service.detected(dir).map { it.tool().id() } }) else emptyMap()
        return Loaded(rows, inheriting, from)
    }

    /** Group only: on = take the members' languages again (the group's own are dropped), off = start an own set from what is inherited. */
    fun inheritFromMembers(on: Boolean) {
        val lsp = groupLsp ?: return
        val id = groupId ?: return
        if (switching) return
        switching = true
        scope.launch {
            try {
                toggleOrder.withLock { withContext(Dispatchers.IO) { if (on) lsp.inherit(id) else lsp.override(id) } }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
            } finally {
                switching = false
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
