package com.github.krpkbhdn.ccmanager.ui.presets.apply

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.krpkbhdn.ccmanager.preset.PresetApplicationService
import com.github.krpkbhdn.ccmanager.preset.ProjectPreset
import com.github.krpkbhdn.ccmanager.preset.apply.PlannedChange
import com.github.krpkbhdn.ccmanager.preset.apply.PresetApplyResult
import com.github.krpkbhdn.ccmanager.preset.apply.PresetApplyService
import com.github.krpkbhdn.ccmanager.preset.apply.TidyClaudeMd
import com.github.krpkbhdn.ccmanager.project.Project
import com.github.krpkbhdn.ccmanager.ui.presets.searchPresets
import com.github.krpkbhdn.ccmanager.ui.state.io
import com.github.krpkbhdn.ccmanager.ui.state.runGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The Apply preset dialog: choose one or more presets (layers), review what it would change in the project, apply the checked changes, read the outcomes.
 * Planning and applying do file and CLI work, so they run off the UI thread; a project chosen first decides the folder.
 */
class ApplyPresetState(
    private val service: PresetApplyService,
    private val applications: PresetApplicationService,
    private val presets: () -> List<ProjectPreset>,
    private val projectOf: (Long) -> Project?,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
    private val recommendedKeys: (Path) -> Set<String>,
    private val startTidySession: (project: Project, prompt: String, onStarted: () -> Unit) -> Unit,
) {
    /** True while the user still has to pick the project this opened without. */
    var pickingProject by mutableStateOf(false)
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var step by mutableStateOf(ApplyStep.CHOOSE)
        private set
    /** The checked presets; they are applied in the order of the preset list. */
    var chosen by mutableStateOf<Set<Long>>(emptySet())
        private set
    var recommended by mutableStateOf<Set<String>>(emptySet())
        private set
    /** True on the result when Claude can be asked to merge duplicate rules in CLAUDE.md files. */
    var tidyOffered by mutableStateOf(false)
        private set
    var applied by mutableStateOf<Map<Long, Int>>(emptyMap())
        private set
    /** Null while the plan is being worked out. */
    var plan by mutableStateOf<List<PlannedChange>?>(null)
        private set
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set
    var expanded by mutableStateOf<Set<Long>>(emptySet())
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<PresetApplyResult?>(null)
        private set
    val query = TextFieldState()

    private var pendingPresetIds: Set<Long> = emptySet()
    private var generation = 0

    val isOpen: Boolean get() = pickingProject || project != null

    val chosenNames: String get() = presets().filter { it.id() in chosen }.joinToString(" + ") { it.name() }

    val visiblePresets: List<ProjectPreset> get() = searchPresets(presets(), query.text.toString())

    /** Opens for [projectId], or first asks for a project when null; with [presetIds] it starts at the review of those. */
    fun open(projectId: Long?, vararg presetIds: Long) {
        if (isOpen) return
        reset()
        val known = projectId?.let(projectOf)
        if (known == null) {
            pendingPresetIds = presetIds.toSet()
            pickingProject = true
        } else {
            begin(known, presetIds.toSet())
        }
    }

    fun chooseProject(picked: Project) {
        pickingProject = false
        begin(picked, pendingPresetIds)
    }

    fun statusOf(preset: ProjectPreset): PresetStatus? = presetStatus(preset, applied[preset.id()])

    fun isRecommended(preset: ProjectPreset): Boolean = preset.bundledKey() in recommended

    fun toggleChosen(id: Long) {
        chosen = if (id in chosen) chosen - id else chosen + id
    }

    fun review() {
        val ids = orderedChoice(presets(), chosen).takeIf { it.isNotEmpty() } ?: return
        val folder = project?.path ?: return
        val mine = ++generation
        step = ApplyStep.REVIEW
        plan = null
        selection = emptySet()
        expanded = emptySet()
        error = null
        scope.launch {
            runGuarded({ if (mine == generation) { error = it; plan = emptyList() } }) {
                val planned = io { service.plan(ids, folder) }
                if (mine == generation) {
                    plan = planned
                    selection = defaultSelection(planned)
                }
            }
        }
    }

    fun back() {
        if (running || step != ApplyStep.REVIEW) return
        generation++
        step = ApplyStep.CHOOSE
        error = null
    }

    fun toggle(change: PlannedChange) {
        if (!isSelectable(change)) return
        val id = change.componentId()
        selection = if (id in selection) selection - id else selection + id
    }

    fun toggleExpanded(id: Long) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    fun apply() {
        val shown = project ?: return
        val ids = orderedChoice(presets(), chosen).takeIf { it.isNotEmpty() } ?: return
        if (running || !canApply(plan, selection)) return
        val checked = selection
        val mine = generation
        running = true
        error = null
        scope.launch {
            try {
                runGuarded({ error = it }) {
                    val outcome = io { service.apply(shown.id, ids, shown.path, checked) }
                    if (mine != generation) return@runGuarded
                    result = outcome
                    tidyOffered = offersTidy(outcome.outcomes()) && io { TidyClaudeMd.applicableTo(shown.path) }
                    running = false
                    step = ApplyStep.RESULT
                    applied = io { applications.applications(shown.id) }
                }
            } finally {
                running = false
            }
        }
    }

    /** Starts a normal interactive Claude session that proposes merging duplicate rules; the dialog closes once it started. */
    fun tidy() {
        val shown = project ?: return
        startTidySession(shown, TidyClaudeMd.prompt(), ::close)
    }

    fun close() {
        generation++
        reset()
    }

    private fun begin(target: Project, startAt: Set<Long>) {
        project = target
        val initial = startAt.ifEmpty { setOfNotNull(presets().firstOrNull()?.id()) }
        chosen = initial
        scope.launch { runGuarded(reportError) { applied = io { applications.applications(target.id) } } }
        if (startAt.isNotEmpty()) review() else recommend(target, initial)
    }

    /** Preselects the recommended layers unless the user already changed the choice meanwhile. */
    private fun recommend(target: Project, initial: Set<Long>) {
        val mine = generation
        scope.launch {
            runGuarded(reportError) {
                val keys = io { runCatching { recommendedKeys(target.path) }.getOrDefault(emptySet()) }
                if (mine != generation) return@runGuarded
                recommended = keys
                if (chosen == initial) chosen = recommendedIds(presets(), keys).ifEmpty { initial }
            }
        }
    }

    private fun reset() {
        pickingProject = false
        pendingPresetIds = emptySet()
        project = null
        step = ApplyStep.CHOOSE
        chosen = emptySet()
        recommended = emptySet()
        tidyOffered = false
        applied = emptyMap()
        plan = null
        selection = emptySet()
        expanded = emptySet()
        running = false
        error = null
        result = null
        query.edit { replace(0, length, "") }
    }
}
