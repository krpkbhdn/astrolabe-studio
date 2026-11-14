package com.github.krpkbhdn.ccmanager.ui.codemode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService
import com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.PluginServer
import com.github.krpkbhdn.ccmanager.ui.state.io
import com.github.krpkbhdn.ccmanager.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** The Code Mode setting of one project (and the plugin MCP servers it can offer): shown at once, every change is saved at once on IO, a failed save reloads what is stored, and a load never overwrites a newer change. */
class ProjectCodeModeState(
    private val service: ProjectCodeModeService?,
    private val projectId: Long,
    private val scope: CoroutineScope,
    private val pluginServers: () -> List<PluginServer> = { emptyList() },
    private val reportError: (String) -> Unit,
) {
    var enabled by mutableStateOf(false)
        private set
    var servers by mutableStateOf<List<String>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var plugins by mutableStateOf<List<PluginServer>>(emptyList())
        private set
    val available: Boolean get() = service != null
    private val saveOrder = Mutex()
    private var latestChange = 0
    private val pendingSaves = AtomicInteger()

    fun load() {
        val version = latestChange
        scope.launch {
            runGuarded(reportError) {
                val stored = io { service?.get(projectId) }
                plugins = io { runCatching(pluginServers).getOrDefault(emptyList()) }
                if (version == latestChange && pendingSaves.get() == 0) adopt(stored) else loaded = true
            }
        }
    }

    fun switchOn(on: Boolean) = change(on, servers)

    fun setServer(name: String, on: Boolean) = change(enabled, withServer(servers, name, on))

    private fun change(newEnabled: Boolean, newServers: List<String>) {
        enabled = newEnabled
        servers = newServers
        val version = ++latestChange
        pendingSaves.incrementAndGet()
        scope.launch {
            try {
                runGuarded(reportError) {
                    saveOrder.withLock {
                        try {
                            adoptIfLatest(version, io { service?.save(projectId, newEnabled, newServers) })
                        } catch (e: RuntimeException) {
                            adoptIfLatest(version, io { service?.get(projectId) })
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
        stored?.let { enabled = it.enabled(); servers = it.servers() }
        loaded = true
    }
}
