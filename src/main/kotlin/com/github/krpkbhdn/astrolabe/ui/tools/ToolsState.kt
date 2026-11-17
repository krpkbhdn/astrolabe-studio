package com.github.krpkbhdn.astrolabe.ui.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.toolchain.GroupLspService
import com.github.krpkbhdn.astrolabe.toolchain.LspHealthMonitor
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupService
import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolsService
import com.github.krpkbhdn.astrolabe.toolchain.ToolCatalog
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallResult
import com.github.krpkbhdn.astrolabe.toolchain.ToolProbe
import com.github.krpkbhdn.astrolabe.toolchain.ToolStatus
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Toolchain beans the UI talks to; [projectTools] is for the per-project tools UI. */
data class ToolchainBackend(val catalog: ToolCatalog, val probe: ToolProbe, val installer: ToolInstallService, val projectTools: ProjectToolsService, val lspSetup: LspSetupService, val health: LspHealthMonitor, val groupLsp: GroupLspService? = null)

/** Where the install dialog stands: confirm the command, watch it run, read the result. */
sealed interface InstallFlow {
    data object Idle : InstallFlow
    data class Confirm(val plan: InstallPlan) : InstallFlow
    /** The tool has no automatic command for this system: the dialog only shows how to install it by hand. */
    data class Manual(val plan: InstallPlan) : InstallFlow
    data class Running(val plan: InstallPlan, val log: List<String>) : InstallFlow
    data class Done(val plan: InstallPlan, val result: InstallResult, val log: List<String>) : InstallFlow
    /** Uninstall of a tool this app downloaded: confirm, wait, read the result. */
    data class ConfirmRemove(val plan: InstallPlan) : InstallFlow
    data class Removing(val plan: InstallPlan) : InstallFlow
    data class Removed(val plan: InstallPlan, val result: InstallResult) : InstallFlow
}

val InstallFlow.isBusy: Boolean get() = this is InstallFlow.Running || this is InstallFlow.Removing

/** Compose state of the Tools settings page: catalog, probe results, and the install flow ([isBlocked]: a setup owns the install slot). Probes and installs run off the UI thread. */
class ToolsState(
    private val backend: ToolchainBackend?,
    private val scope: CoroutineScope,
    private val isBlocked: () -> Boolean = { false },
    private val reportError: (title: String, message: String) -> Unit,
) {
    var tools by mutableStateOf<List<ToolDefinition>>(emptyList())
        private set
    val statuses = mutableStateMapOf<String, ToolStatus>()
    val plans = mutableStateMapOf<String, InstallPlan>()
    private val checking = mutableStateListOf<String>()
    private var pendingFullChecks by mutableStateOf(0)
    private val writeOrder = Mutex()
    private var latestFullCheck = 0
    private val clock = java.util.concurrent.atomic.AtomicInteger()
    private val writtenAt = mutableMapOf<String, Int>()
    val checkingAll: Boolean get() = pendingFullChecks > 0
    private val managed = mutableStateListOf<String>()
    private val selectedChannels = mutableStateMapOf<String, ToolChannel>()
    private val installedChannels = mutableStateMapOf<String, ToolChannel>()
    val installRunning: Boolean get() = install.isBusy || backend?.installer?.isInstalling() == true
    var install by mutableStateOf<InstallFlow>(InstallFlow.Idle)
        private set

    val groups: List<ToolGroup> get() = groupTools(tools, statuses)

    fun isChecking(id: String): Boolean = checkingAll || id in checking

    fun nameOf(id: String): String? = tools.firstOrNull { it.id == id }?.name

    /** True for a tool this app downloaded itself, which can be updated and removed here. */
    fun isManaged(id: String): Boolean = id in managed

    /** Opening the page shows cached results; the Check all button probes again. */
    fun open() = checkAll(fresh = false)

    /** Install plans only, for a screen that shows its own probe results and just needs the Install buttons. */
    fun loadPlans() {
        val tooling = backend ?: return
        scope.launch {
            try {
                writeOrder.withLock {
                    val all = io { tooling.catalog.all() }
                    tools = all
                    refreshPlans(tooling, all)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Could not prepare installers", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun checkAll() = checkAll(fresh = true)

    private fun checkAll(fresh: Boolean) {
        val tooling = backend ?: return
        val request = ++latestFullCheck
        val startedAt = clock.incrementAndGet()
        pendingFullChecks++
        scope.launch {
            try {
                val (all, found) = io { tooling.catalog.all() to (if (fresh) tooling.probe.probeAllFresh() else tooling.probe.probeAll()) }
                writeOrder.withLock {
                    if (request == latestFullCheck) {
                        tools = all
                        found.filter { (writtenAt[it.id] ?: 0) < startedAt }.forEach { statuses[it.id] = it }
                    }
                    refreshPlans(tooling, tools)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Could not check tools", e.message ?: e.javaClass.simpleName)
            } finally {
                pendingFullChecks--
            }
        }
    }

    fun check(id: String) {
        val tooling = backend ?: return
        if (id in checking) return
        checking += id
        val startedAt = clock.incrementAndGet()
        scope.launch {
            try {
                val status = io { tooling.probe.probeFresh(id) }
                writeOrder.withLock {
                    if ((writtenAt[id] ?: 0) < startedAt) record(id, status)
                    refreshPlans(tooling, tools)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Could not check ${nameOf(id) ?: id}", e.message ?: e.javaClass.simpleName)
            } finally {
                checking -= id
            }
        }
    }

    /** The channel the next install or update of [id] uses. */
    fun selectedChannel(id: String): ToolChannel = selectedChannels[id] ?: ToolChannel.VERIFIED

    /** The channel of the installed copy, null when unknown or not installed by the app. */
    fun installedChannel(id: String): ToolChannel? = installedChannels[id]

    /** Only stores the choice; Install or Update applies it. */
    fun selectChannel(id: String, channel: ToolChannel) {
        val tooling = backend ?: return
        selectedChannels[id] = channel
        scope.launch {
            try {
                io { tooling.installer.selectChannel(id, channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Could not save the version choice", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Switches to the verified version and opens the install confirm for it. */
    fun useVerifiedVersion(id: String) {
        if (install != InstallFlow.Idle || isBlocked()) return
        selectChannel(id, ToolChannel.VERIFIED)
        requestInstall(id)
    }

    fun requestInstall(id: String) {
        val plan = plans[id] ?: return
        if (install != InstallFlow.Idle || isBlocked()) return
        if (plan.command().isEmpty() && plan.download().isEmpty) install = InstallFlow.Manual(plan)
        else if (plan.blocker().isEmpty) install = InstallFlow.Confirm(plan)
    }

    fun confirmInstall() {
        val tooling = backend ?: return
        val confirm = install as? InstallFlow.Confirm ?: return
        if (isBlocked()) return
        val id = confirm.plan.tool().id()
        install = InstallFlow.Running(confirm.plan, emptyList())
        scope.launch {
            try {
                val result = io { tooling.installer.install(id) { text -> scope.launch { showLog(confirm.plan, text) } } }
                writeOrder.withLock {
                    if (result.outcome() != ToolInstallService.Outcome.BLOCKED) record(id, result.status())
                    // Done is published after the status, so a check started on seeing Done gets a newer stamp
                    install = InstallFlow.Done(confirm.plan, result, (install as? InstallFlow.Running)?.log.orEmpty())
                    refreshPlans(tooling, tools)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                install = InstallFlow.Idle
                reportError("Install failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun requestUninstall(id: String) {
        val plan = plans[id] ?: return
        if (install == InstallFlow.Idle && !isBlocked() && isManaged(id)) install = InstallFlow.ConfirmRemove(plan)
    }

    fun confirmUninstall() {
        val tooling = backend ?: return
        val confirm = install as? InstallFlow.ConfirmRemove ?: return
        if (isBlocked()) return
        val id = confirm.plan.tool().id()
        install = InstallFlow.Removing(confirm.plan)
        scope.launch {
            try {
                val result = io { tooling.installer.uninstall(id) }
                install = InstallFlow.Removed(confirm.plan, result)
                writeOrder.withLock {
                    record(id, result.status())
                    refreshPlans(tooling, tools)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                install = InstallFlow.Idle
                reportError("Removal failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Interrupts the running install only; its result then arrives through the normal Done step. */
    fun cancelInstall() {
        backend?.installer?.cancel()
    }

    /** Closing is refused while the installer runs. */
    fun closeInstall() {
        if (!install.isBusy) install = InstallFlow.Idle
    }

    fun openDocs(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    private fun showLog(plan: InstallPlan, output: String) {
        if (install is InstallFlow.Running) install = InstallFlow.Running(plan, logLinesOf(output))
    }

    /** A full check that started before this write must not overwrite it, see [writtenAt]. */
    private fun record(id: String, status: ToolStatus) {
        statuses[id] = status
        writtenAt[id] = clock.incrementAndGet()
    }

    private suspend fun refreshPlans(tooling: ToolchainBackend, all: List<ToolDefinition>) {
        val fresh = io { all.mapNotNull { tool -> tooling.installer.plan(tool.id).orElse(null) } }
        plans.keys.retainAll(fresh.map { it.tool().id() }.toSet())
        fresh.forEach { plans[it.tool().id()] = it }
        val managedNow = io { all.map { it.id }.filter(tooling.installer::isManaged) }
        managed.retainAll(managedNow.toSet())
        managed += managedNow.filter { it !in managed }
        val channelTools = all.filter { it.supportsChannels() }.map { it.id }
        val channels = io { channelTools.associateWith { tooling.installer.selectedChannel(it) } }
        val installed = io { channelTools.mapNotNull { id -> tooling.installer.installedChannel(id)?.let { id to it } }.toMap() }
        selectedChannels.putAll(channels)
        installedChannels.keys.retainAll(installed.keys)
        installedChannels.putAll(installed)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
