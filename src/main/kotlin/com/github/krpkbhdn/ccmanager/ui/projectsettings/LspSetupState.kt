package com.github.krpkbhdn.ccmanager.ui.projectsettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.toolchain.LspSetupService
import com.github.krpkbhdn.ccmanager.toolchain.LspSetupStep
import com.github.krpkbhdn.ccmanager.ui.tools.logLinesOf
import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(LspSetupState::class.java)
private const val INSTALL_BUSY_NOTICE = "A tool install is running. Wait for it to finish, then confirm again."

/** Where the one-click setup stands: confirm the plan, watch it run, read what each step did. */
sealed interface LspSetupFlow {
    data object Idle : LspSetupFlow
    data class Confirm(val project: Project, val plan: List<LspSetupStep>) : LspSetupFlow
    data class Running(val project: Project, val plan: List<LspSetupStep>, val log: List<String>) : LspSetupFlow
    data class Done(val project: Project, val results: List<LspSetupStep>) : LspSetupFlow
}

/** Sets up all detected languages of a project in one go; one setup at a time, never together with a single tool install, all work off the UI thread. */
class LspSetupState(
    private val service: LspSetupService?,
    private val scope: CoroutineScope,
    private val installBusy: () -> Boolean,
    private val onFinished: (Project) -> Unit,
    private val reportError: (title: String, message: String) -> Unit,
) {
    var flow by mutableStateOf<LspSetupFlow>(LspSetupFlow.Idle)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private val planByProject = mutableStateMapOf<Long, List<LspSetupStep>>()
    private val requests = AtomicLong()
    private val latestRequest = ConcurrentHashMap<Long, Long>()

    fun plannedFor(projectId: Long): List<LspSetupStep> = planByProject[projectId].orEmpty()

    val running: Boolean get() = flow is LspSetupFlow.Running

    /** Loads the plan behind the page button. */
    fun loadPlan(project: Project) {
        val setup = service ?: return
        val request = newRequest(project)
        scope.launch {
            try {
                store(project, request, withContext(Dispatchers.IO) { setup.plan(project.path) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not plan the language server setup of project {}: {}", project.id, e.javaClass.simpleName)
                store(project, request, emptyList())
            }
        }
    }

    fun start(project: Project) {
        val setup = service ?: return
        if (flow != LspSetupFlow.Idle || installBusy()) return
        val request = newRequest(project)
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { setup.plan(project.path) }
                store(project, request, plan)
                if (pendingStepCount(plan) > 0 && flow == LspSetupFlow.Idle && !installBusy()) {
                    notice = null
                    flow = LspSetupFlow.Confirm(project, plan)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Could not prepare the setup", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun confirm() {
        val setup = service ?: return
        val confirm = flow as? LspSetupFlow.Confirm ?: return
        if (installBusy()) {
            notice = INSTALL_BUSY_NOTICE
            return
        }
        flow = LspSetupFlow.Running(confirm.project, confirm.plan, emptyList())
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    setup.run(confirm.project.path, confirm.plan) { text -> scope.launch { showLog(confirm, text) } }
                }
                flow = LspSetupFlow.Done(confirm.project, results)
                onFinished(confirm.project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow = LspSetupFlow.Idle
                reportError("Setup failed", e.message ?: e.javaClass.simpleName)
                onFinished(confirm.project)
            }
        }
    }

    /** Interrupts the running install; the remaining steps are skipped and the result arrives through Done. */
    fun cancel() {
        service?.cancel()
    }

    /** Closing is refused while the setup runs. */
    fun close() {
        if (!running) {
            flow = LspSetupFlow.Idle
            notice = null
        }
    }

    private fun newRequest(project: Project): Long = requests.incrementAndGet().also { latestRequest[project.id] = it }

    private fun store(project: Project, request: Long, plan: List<LspSetupStep>) {
        if (latestRequest[project.id] == request) planByProject[project.id] = plan
    }

    private fun showLog(confirm: LspSetupFlow.Confirm, output: String) {
        if (flow is LspSetupFlow.Running) flow = LspSetupFlow.Running(confirm.project, confirm.plan, logLinesOf(output))
    }
}
