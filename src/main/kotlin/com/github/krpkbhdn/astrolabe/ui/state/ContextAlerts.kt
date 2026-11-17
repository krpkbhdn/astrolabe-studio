package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.mutableStateSetOf
import com.github.krpkbhdn.astrolabe.usage.SessionUsage

/** Sessions whose "compact?" marker the user closed; a session leaves the set once its context is no longer critical, so the next fill-up alerts again. */
class ContextAlerts {
    private val dismissed = mutableStateSetOf<String>()

    fun shouldAlert(astrolabeSessionId: String, view: ContextUsageView?): Boolean = view?.level == UsageLevel.CRITICAL && astrolabeSessionId !in dismissed

    fun dismiss(astrolabeSessionId: String) {
        dismissed.add(astrolabeSessionId)
    }

    fun rearmBelowCritical(usage: Map<String, SessionUsage>) {
        dismissed.removeAll { contextUsageView(usage[it])?.level != UsageLevel.CRITICAL }
    }
}
