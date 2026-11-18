package com.github.krpkbhdn.astrolabe.ui.presets.apply

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.astrolabe.preset.PresetApplicationService
import com.github.krpkbhdn.astrolabe.preset.ProjectPreset
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.ui.state.io
import com.github.krpkbhdn.astrolabe.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

private const val GENERAL_KEY = "general"

/** The question "Set up this project?"; [presetIds] are the layers recommended for it, in layer order. */
class PresetOffer(val project: Project, val presetIds: List<Long>)

/**
 * Offers a preset when a project without any applied preset is opened: once per run and project, never while another dialog is in the way
 * (then the next visit asks), and never again after "Not for this project".
 */
class PresetOfferState(
    private val applications: PresetApplicationService,
    private val presets: () -> List<ProjectPreset>,
    private val projectOf: (Long) -> Project?,
    private val scope: CoroutineScope,
    private val blocked: () -> Boolean,
    private val review: (projectId: Long, presetIds: List<Long>) -> Unit,
    private val reportError: (String) -> Unit,
    private val recommendedKeys: (Path) -> Set<String>,
) {
    var offer by mutableStateOf<PresetOffer?>(null)
        private set

    private val asked = mutableSetOf<Long>()

    fun offerFor(projectId: Long) {
        val project = projectOf(projectId)?.takeIf { !it.group }
        if (project == null || blocked() || projectId in asked) return
        scope.launch {
            runGuarded(reportError) {
                val (wanted, keys) = io { (applications.applications(projectId).isEmpty() && !applications.offerDeclined(projectId)) to runCatching { recommendedKeys(project.path) }.getOrDefault(emptySet()) }
                val all = presets()
                if (!wanted) {
                    asked += projectId
                } else if (!blocked() && offer == null && all.isNotEmpty()) {
                    asked += projectId
                    offer = PresetOffer(project, offeredIds(all, keys))
                }
            }
        }
    }

    fun reviewAndApply() {
        val shown = offer ?: return
        offer = null
        review(shown.project.id, shown.presetIds)
    }

    private fun offeredIds(all: List<ProjectPreset>, keys: Set<String>): List<Long> =
        orderedChoice(all, recommendedIds(all, keys)).ifEmpty { listOf((all.firstOrNull { it.bundledKey() == GENERAL_KEY } ?: all.first()).id()) }

    fun decline() {
        val shown = offer ?: return
        offer = null
        scope.launch { runGuarded(reportError) { io { applications.declineOffer(shown.project.id) } } }
    }

    fun later() {
        offer = null
    }
}
