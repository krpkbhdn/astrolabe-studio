package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.update.AvailableUpdate
import com.github.krpkbhdn.astrolabe.update.UpdateService
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.util.Optional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPDATE_FAILED_TITLE = "Update failed"
private const val UNKNOWN_ERROR = "Something went wrong"

/** Compose state of the self-update flow: periodic check, download on request, then on a second click stage it, confirm and restart to install. */
class UpdateState(
    private val updates: UpdateService?,
    private val scope: CoroutineScope,
    private val reportError: (title: String, message: String) -> Unit,
    private val confirmInstall: (message: String, install: () -> Unit, cancel: () -> Unit) -> Unit,
    private val exitApp: () -> Unit,
) {
    var status by mutableStateOf<UpdateStatus>(UpdateStatus.None)
        private set
    var dismissed by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    /** True after a manual check that found nothing newer; cleared when the next one starts. */
    var upToDate by mutableStateOf(false)
        private set

    /** True after a manual check whose request failed; cleared when the next one starts. */
    var checkFailed by mutableStateOf(false)
        private set

    /** The offer's button, worked out on IO when the update was found: the backend's support check may run package manager queries, so composition only reads it. */
    private var resolved by mutableStateOf<ResolvedAction?>(null)

    private data class ResolvedAction(val update: AvailableUpdate, val action: UpdateAction)

    /** The button and note for an offered update; releases without an installer for this installation (and an update not resolved yet) only open their page. */
    fun actionFor(update: AvailableUpdate): UpdateAction =
        resolved?.takeIf { it.update == update }?.action ?: updateActionOf(false, false)

    val enabled: Boolean get() = updates?.enabled() == true
    val currentVersion: String get() = updates?.currentVersion().orEmpty()

    fun start(): Job? {
        val service = updates?.takeIf { it.enabled() } ?: return null
        return scope.launch {
            service.lastOutcome().map(::outcomeToast).orElse(null)?.let { reportError(UPDATE_FAILED_TITLE, it) }
            while (true) {
                check(service, service::findUpdate)
                delay(service.checkInterval().toMillis())
            }
        }
    }

    fun checkNow() {
        val service = updates?.takeIf { it.enabled() } ?: return
        if (checking) return
        checking = true
        upToDate = false
        checkFailed = false
        scope.launch {
            try {
                check(service, service::checkForUpdate)
                upToDate = status == UpdateStatus.None
            } catch (e: IllegalStateException) {
                checkFailed = true
            } finally {
                checking = false
            }
        }
    }

    fun dismiss() {
        dismissed = true
    }

    fun download() {
        val service = updates ?: return
        val offered = (status as? UpdateStatus.Available)?.update ?: return
        status = UpdateStatus.Downloading(offered, null)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    service.download(offered) { fraction -> scope.launch { status = UpdateStatus.Downloading(offered, fraction.toFloat()) } }
                }
                status = UpdateStatus.Ready(offered, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                status = UpdateStatus.Available(offered)
                reportError(UPDATE_FAILED_TITLE, e.message ?: UNKNOWN_ERROR)
            }
        }
    }

    /** Stages the installer off the UI thread first, then asks (naming the update) before the app quits to install it. */
    fun restart() {
        val service = updates ?: return
        val ready = status as? UpdateStatus.Ready ?: return
        status = UpdateStatus.Preparing(ready.update)
        scope.launch {
            val (prepared, needsAuthentication) = try {
                withContext(Dispatchers.IO) { service.prepareInstall(ready.installer) to service.needsAuthentication(ready.update) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                status = ready
                reportError(UPDATE_FAILED_TITLE, e.message ?: UNKNOWN_ERROR)
                return@launch
            }
            status = ready
            confirmInstall(
                installConfirmMessage(ready.update.version, needsAuthentication),
                {
                    try {
                        service.launchAfterExit(prepared)
                        exitApp()
                    } catch (e: IllegalStateException) {
                        service.discard(prepared)
                        reportError(UPDATE_FAILED_TITLE, e.message ?: UNKNOWN_ERROR)
                    }
                },
                { scope.launch(Dispatchers.IO) { service.discard(prepared) } },
            )
        }
    }

    fun openReleasePage() {
        val update = (status as? UpdateStatus.Available)?.update ?: return
        browse(update.releasePageUri())
    }

    fun openReleases() {
        browse(updates?.releasesUri() ?: return)
    }

    private fun browse(uri: URI) {
        try {
            Desktop.getDesktop().browse(uri)
        } catch (e: IOException) {
            reportError(UPDATE_FAILED_TITLE, e.message ?: UNKNOWN_ERROR)
        } catch (e: UnsupportedOperationException) {
            reportError(UPDATE_FAILED_TITLE, e.message ?: UNKNOWN_ERROR)
        }
    }

    private fun busy() = status is UpdateStatus.Downloading || status is UpdateStatus.Preparing || status is UpdateStatus.Ready

    private suspend fun check(service: UpdateService, find: () -> Optional<AvailableUpdate>) {
        if (busy()) return
        val (found, action) = withContext(Dispatchers.IO) { find().orElse(null)?.let { it to updateActionOf(service.canInstall(it), service.needsAuthentication(it)) } } ?: return
        // read again: a download may have started while the request was out, and the offer must not replace it (the scope is the single UI thread)
        if (busy()) return
        resolved = ResolvedAction(found, action)
        if ((status as? UpdateStatus.Available)?.update != found) {
            status = UpdateStatus.Available(found)
            dismissed = false
        }
    }
}
