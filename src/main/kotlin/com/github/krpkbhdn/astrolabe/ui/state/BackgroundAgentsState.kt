package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.mutableStateMapOf
import com.github.krpkbhdn.astrolabe.claude.launch.BackgroundAgent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Background agents per project, refreshed off the UI thread; a refresh already running for a project is not repeated. */
class BackgroundAgentsState(
    private val scope: CoroutineScope,
    private val list: (Path) -> List<BackgroundAgent>,
    private val reportError: (String) -> Unit,
) {
    private val agents = mutableStateMapOf<Long, List<BackgroundAgent>>()
    private val refreshing = mutableSetOf<Long>()
    private val removedWhileRefreshing = ConcurrentHashMap.newKeySet<Long>()

    operator fun get(projectId: Long): List<BackgroundAgent>? = agents[projectId]

    fun refresh(projectId: Long, path: Path) {
        if (!refreshing.add(projectId)) return
        scope.launch {
            try {
                runGuarded(reportError) {
                    val listed = io { list(path) }
                    if (projectId !in removedWhileRefreshing) agents[projectId] = listed
                }
            } finally {
                refreshing.remove(projectId)
                removedWhileRefreshing.remove(projectId)
            }
        }
    }

    fun retain(projectIds: Set<Long>) {
        agents.keys.retainAll(projectIds)
        removedWhileRefreshing.addAll(refreshing.filterNot { it in projectIds })
    }
}
