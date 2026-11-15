package com.github.krpkbhdn.ccmanager.ui.trust

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.project.TrustLevel
import com.github.krpkbhdn.ccmanager.project.TrustSettingsService
import com.github.krpkbhdn.ccmanager.ui.state.io
import com.github.krpkbhdn.ccmanager.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The app-wide default trust level (Settings page "Trust", ASK or TRUSTED) and the open flag of the status bar quick popup; the service subscription lives as long as the app. */
class TrustState(
    private val service: TrustSettingsService?,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit = {},
) {
    var defaultLevel by mutableStateOf(service?.defaultLevel() ?: TrustLevel.ASK)
        private set
    var open by mutableStateOf(false)

    init {
        service?.subscribe { scope.launch { defaultLevel = io { service.defaultLevel() } } }
    }

    fun setDefault(level: TrustLevel) {
        val target = service ?: return
        scope.launch { runGuarded(reportError) { io { target.setDefaultLevel(level) } } }
    }
}
