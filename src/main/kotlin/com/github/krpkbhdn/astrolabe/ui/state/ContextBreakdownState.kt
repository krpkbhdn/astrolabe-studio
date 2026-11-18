package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import com.github.krpkbhdn.astrolabe.usage.context.ContextBreakdown
import com.github.krpkbhdn.astrolabe.usage.context.ContextBreakdownResult
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a session's `/context` is measured: its working folder and the Claude session to resume, null for a fresh estimate. */
data class ContextTarget(val directory: Path, val claudeSessionId: String?)

sealed interface BreakdownLoad {
    data object Loading : BreakdownLoad
    data class Done(val breakdown: ContextBreakdown) : BreakdownLoad
    data class Failed(val reason: String) : BreakdownLoad
}

/** Per-session `/context` measurements, taken off the UI thread only when the context dialog asks for them. */
class ContextBreakdownState(
    private val measure: (target: ContextTarget, force: Boolean) -> ContextBreakdownResult,
    private val scope: CoroutineScope,
    private val targetOf: (astrolabeSessionId: String) -> ContextTarget?,
) {
    private val loads = mutableStateMapOf<String, BreakdownLoad>()

    private val inFlight = mutableStateSetOf<String>()

    fun loadOf(astrolabeSessionId: String): BreakdownLoad? = loads[astrolabeSessionId]

    /** True while a measurement runs, also when an older result is still displayed. */
    fun isLoading(astrolabeSessionId: String): Boolean = astrolabeSessionId in inFlight

    fun load(astrolabeSessionId: String, force: Boolean) {
        val target = targetOf(astrolabeSessionId)
        if (target == null) {
            loads[astrolabeSessionId] = BreakdownLoad.Failed("The session is not running.")
            return
        }
        if (!inFlight.add(astrolabeSessionId)) return
        if (loads[astrolabeSessionId] !is BreakdownLoad.Done) loads[astrolabeSessionId] = BreakdownLoad.Loading
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { measure(target, force) }
                loads[astrolabeSessionId] = result.breakdown()?.let { BreakdownLoad.Done(it) } ?: BreakdownLoad.Failed(result.failure() ?: "Breakdown unavailable")
            } finally {
                inFlight.remove(astrolabeSessionId)
            }
        }
    }
}
