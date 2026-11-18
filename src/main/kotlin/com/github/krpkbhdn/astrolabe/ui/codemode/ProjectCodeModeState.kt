package com.github.krpkbhdn.astrolabe.ui.codemode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeMode
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService
import com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess
import com.github.krpkbhdn.astrolabe.extension.mcp.PluginMcpServers.PluginServer
import com.github.krpkbhdn.astrolabe.ui.projectsettings.sourcesOf
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** The Code Mode setting of one project or group (a group that inherits shows its members' merge and is read-only until [override]) (and the plugin MCP servers it can offer): shown at once, every change is saved at once on IO, a failed save reloads what is stored, and a load never overwrites a newer change. */
class ProjectCodeModeState(
    private val service: ProjectCodeModeService?,
    private val projectId: Long,
    private val scope: CoroutineScope,
    private val pluginServers: () -> List<PluginServer> = { emptyList() },
    private val group: Boolean = false,
    private val memberServers: () -> List<Pair<String, List<String>>> = { emptyList() },
    private val reportError: (String) -> Unit,
) {
    var enabled by mutableStateOf(false)
        private set
    var servers by mutableStateOf<List<String>>(emptyList())
        private set
    var toolCalls by mutableStateOf(ToolCallAccess.ASK)
        private set
    var loaded by mutableStateOf(false)
        private set
    var plugins by mutableStateOf<List<PluginServer>>(emptyList())
        private set
    /** A group without its own setting: shows the merge of its members and cannot be edited. */
    var inherits by mutableStateOf(false)
        private set

    /** Member names per server while [inherits], for "From ..." notes. */
    var sources by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    val available: Boolean get() = service != null
    private val saveOrder = Mutex()
    private var latestChange = 0
    private val pendingSaves = AtomicInteger()

    /** Reloads when another state (the status bar chip or the settings dialog) saves the same project. */
    private val unsubscribe: Runnable? = service?.subscribe { load() }

    fun dispose() {
        unsubscribe?.run()
    }

    fun load() {
        val version = latestChange
        scope.launch {
            runGuarded(reportError) {
                val stored = io { read() }
                val inheriting = io { group && service?.inherits(projectId) == true }
                val from = if (inheriting) io { sourcesOf(memberServers()) } else emptyMap()
                plugins = io { runCatching(pluginServers).getOrDefault(emptyList()) }
                if (version == latestChange && pendingSaves.get() == 0) {
                    inherits = inheriting
                    sources = from
                    adopt(stored)
                } else loaded = true
            }
        }
    }

    private fun read(): ProjectCodeMode? = if (group) service?.effective(projectId) else service?.get(projectId)

    /** Group only: saves the inherited values as the group's own so they can be edited. */
    fun override() = switchInheritance { service?.override(projectId) }

    /** Group only: drops the group's own setting, the members' merge applies again. */
    fun inherit() = switchInheritance { service?.inherit(projectId) }

    private fun switchInheritance(action: () -> Unit) {
        if (!group) return
        scope.launch { runGuarded(reportError) { io(action); load() } }
    }

    fun switchOn(on: Boolean) = change(on, servers, toolCalls)

    fun chooseToolCalls(access: ToolCallAccess) = change(enabled, servers, access)

    fun setServer(name: String, on: Boolean) = change(enabled, withServer(servers, name, on), toolCalls)

    private fun change(newEnabled: Boolean, newServers: List<String>, newAccess: ToolCallAccess) {
        if (inherits) return
        enabled = newEnabled
        servers = newServers
        toolCalls = newAccess
        val version = ++latestChange
        pendingSaves.incrementAndGet()
        scope.launch {
            try {
                runGuarded(reportError) {
                    saveOrder.withLock {
                        try {
                            adoptIfLatest(version, io { service?.save(projectId, newEnabled, newServers, newAccess) })
                        } catch (e: RuntimeException) {
                            adoptIfLatest(version, io { read() })
                            throw e
                        }
                    }
                }
            } finally {
                pendingSaves.decrementAndGet()
            }
        }
    }

    /** A save that a newer change has overtaken must not overwrite what the user sees now; saves run one after the other. */
    private fun adoptIfLatest(version: Int, stored: ProjectCodeMode?) {
        if (version == latestChange) adopt(stored)
    }

    private fun adopt(stored: ProjectCodeMode?) {
        stored?.let { enabled = it.enabled(); servers = it.servers(); toolCalls = it.toolCalls() }
        loaded = true
    }
}
