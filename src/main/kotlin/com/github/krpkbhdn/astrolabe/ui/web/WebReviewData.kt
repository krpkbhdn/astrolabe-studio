package com.github.krpkbhdn.astrolabe.ui.web

import androidx.compose.runtime.mutableStateMapOf
import com.github.krpkbhdn.astrolabe.review.ReviewStatus
import com.github.krpkbhdn.astrolabe.ui.state.Refresher
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import com.github.krpkbhdn.astrolabe.web.WebItem
import com.github.krpkbhdn.astrolabe.web.WebItemStatus
import com.github.krpkbhdn.astrolabe.web.WebPage
import com.github.krpkbhdn.astrolabe.web.WebReviewService
import com.github.krpkbhdn.astrolabe.web.WebRevision
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

private class ProjectData(val revisions: List<WebRevision>, val pages: List<WebPage>, val items: Map<Long, List<WebItem>>)

/** Observable cache of the revisions, pages and items of the watched projects, reloaded off the UI thread whenever the service changes. */
internal class WebReviewData(
    private val service: WebReviewService,
    scope: CoroutineScope,
    reportError: (String) -> Unit,
    private val onReloaded: suspend (Long) -> Unit,
) {
    private val revisionsByProject = mutableStateMapOf<Long, List<WebRevision>>()
    private val pagesByProject = mutableStateMapOf<Long, List<WebPage>>()
    private val itemsByRevision = mutableStateMapOf<Long, List<WebItem>>()
    private val watchedProjects = ConcurrentHashMap.newKeySet<Long>()
    private val subscription = lazy { service.subscribe(Runnable { reload.request() }) }
    private val reload = Refresher(scope) {
        watchedProjects.toList().forEach { projectId ->
            runGuarded(reportError) {
                val data = io { load(projectId) }
                if (projectId !in watchedProjects) return@runGuarded
                (revisionsByProject[projectId].orEmpty().map { it.id() } - data.items.keys).forEach(itemsByRevision::remove)
                revisionsByProject[projectId] = data.revisions
                pagesByProject[projectId] = data.pages
                data.items.forEach { (revisionId, items) -> itemsByRevision[revisionId] = items }
                onReloaded(projectId)
            }
        }
    }

    private fun load(projectId: Long): ProjectData {
        val revisions = service.revisions(projectId)
        return ProjectData(revisions, service.pages(projectId), revisions.associate { it.id() to service.allItems(it.id()) })
    }

    fun watch(projectId: Long) {
        subscription.value
        if (watchedProjects.add(projectId)) reload.request()
    }

    fun revisions(projectId: Long): List<WebRevision> {
        watch(projectId)
        return revisionsByProject[projectId].orEmpty()
    }

    fun pages(projectId: Long): List<WebPage> {
        watch(projectId)
        return pagesByProject[projectId].orEmpty()
    }

    fun items(revisionId: Long): List<WebItem> = itemsByRevision[revisionId].orEmpty()

    fun pendingCount(projectId: Long): Int {
        val pending = revisions(projectId).firstOrNull { it.status() == ReviewStatus.PENDING } ?: return 0
        return items(pending.id()).count { it.status() == WebItemStatus.OPEN }
    }

    fun projects(): Set<Long> = watchedProjects + revisionsByProject.keys

    fun forget(projectId: Long) {
        watchedProjects.remove(projectId)
        revisionsByProject.remove(projectId)?.forEach { itemsByRevision.remove(it.id()) }
        pagesByProject.remove(projectId)
    }

    fun close() {
        if (subscription.isInitialized()) subscription.value.run()
        reload.close()
    }
}
