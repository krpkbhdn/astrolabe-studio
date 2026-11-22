package com.github.krpkbhdn.astrolabe.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.github.krpkbhdn.astrolabe.claude.hook.ActivityEntry
import com.github.krpkbhdn.astrolabe.claude.install.LoginState
import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot
import com.github.krpkbhdn.astrolabe.notification.AppNotification
import com.github.krpkbhdn.astrolabe.notification.NotificationKind
import com.github.krpkbhdn.astrolabe.ui.assistant.AssistantStartupState
import com.github.krpkbhdn.astrolabe.ui.assistant.AssistantState
import com.github.krpkbhdn.astrolabe.ui.assistant.ToolSummary
import com.github.krpkbhdn.astrolabe.ui.author.AuthorProfileState
import com.github.krpkbhdn.astrolabe.ui.git.model.GitHost
import com.github.krpkbhdn.astrolabe.ui.git.model.GitState
import com.github.krpkbhdn.astrolabe.ui.git.model.GitSubView
import com.github.krpkbhdn.astrolabe.ui.history.SessionSearchState
import com.github.krpkbhdn.astrolabe.ui.review.ReviewState
import com.github.krpkbhdn.astrolabe.ui.review.TeamOptions
import com.github.krpkbhdn.astrolabe.ui.web.WebState
import com.github.krpkbhdn.astrolabe.ui.web.browser.JcefPageHost
import com.github.krpkbhdn.astrolabe.ui.web.browser.WebPageHost
import com.github.krpkbhdn.astrolabe.ui.web.browser.JcefRuntime
import com.github.krpkbhdn.astrolabe.ui.sessions.SessionsTab
import com.github.krpkbhdn.astrolabe.ui.prompts.PromptsState
import com.github.krpkbhdn.astrolabe.claude.proposal.Proposal
import com.github.krpkbhdn.astrolabe.ui.proposal.ProposalState
import com.github.krpkbhdn.astrolabe.ui.proposal.proposalHeadline
import com.github.krpkbhdn.astrolabe.ui.codemode.CodeModeChipState
import com.github.krpkbhdn.astrolabe.ui.codemode.CodeModeState
import com.github.krpkbhdn.astrolabe.ui.codemode.ProjectCodeModeState
import com.github.krpkbhdn.astrolabe.ui.extensions.mcp.McpState
import com.github.krpkbhdn.astrolabe.ui.queue.QueueState
import com.github.krpkbhdn.astrolabe.ui.queue.QueuedPrompt
import com.github.krpkbhdn.astrolabe.ui.run.RunState
import com.github.krpkbhdn.astrolabe.ui.settings.CodeModeSettingsState
import com.github.krpkbhdn.astrolabe.ui.trust.TrustState
import com.github.krpkbhdn.astrolabe.ui.settings.SettingsState
import com.github.krpkbhdn.astrolabe.ui.presets.PresetsState
import com.github.krpkbhdn.astrolabe.ui.presets.apply.ApplyPresetState
import com.github.krpkbhdn.astrolabe.ui.presets.apply.PresetOfferState
import com.github.krpkbhdn.astrolabe.ui.teams.TeamsState
import com.github.krpkbhdn.astrolabe.ui.projectsettings.DetectedTechState
import com.github.krpkbhdn.astrolabe.ui.projectsettings.LspSetupState
import com.github.krpkbhdn.astrolabe.ui.tools.InstallFlow
import com.github.krpkbhdn.astrolabe.ui.tools.ToolsState
import com.github.krpkbhdn.astrolabe.ui.usage.UsageState
import com.github.krpkbhdn.astrolabe.ui.voice.VoiceSettingsState
import com.github.krpkbhdn.astrolabe.ui.voice.voiceSettingsOf
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AppState::class.java)
private const val ACTIVITY_LIMIT = 300
private const val COMPACT_COMMAND = "/compact"

/**
 * Single subscription point to the backend: re-reads snapshots on change callbacks (any thread) and exposes
 * Compose state. All state writes happen on [scope], which must run on the UI dispatcher.
 */
class AppState(
    private val backend: Backend,
    private val scope: CoroutineScope,
    settingsStore: UiSettingsStore = UiSettingsStore.inDataDir(backend.dataDir),
    webHostFactory: () -> WebPageHost = { JcefPageHost.open(backend.dataDir, screenshotTimeout = backend.web.screenshotTimeout()) },
) {
    val settings: UiSettings get() = prefs.settings
    var paletteOpen by mutableStateOf(false)
    var shortcutsOpen by mutableStateOf(false)

    var live by mutableStateOf(LiveSessions())
        private set
    val workingStaleAfter: Duration = backend.sessionProperties.workingStaleAfter()
    var now by mutableStateOf(Instant.now())
        private set
    val route: Route get() = navigator.route
    val sessionsTab: SessionsTab get() = navigator.sessionsTab
    val searchFocusRequests: Int get() = navigator.searchFocusRequests
    var windowActive by mutableStateOf(true)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var notificationsOpen by mutableStateOf(false)
    var usageOpen by mutableStateOf(false)
    var contextSessionId by mutableStateOf<String?>(null)
    var contextShowDetails by mutableStateOf(false)
    var modelPickerOpen by mutableStateOf(false)
    var quickRequestOpen by mutableStateOf(false)
    var assistantHelpOpen by mutableStateOf(false)
    val assistantTools: List<ToolSummary> = backend.assistantTools.map { ToolSummary(it.name(), it.description()) }
    var loginBannerDismissed by mutableStateOf(false)

    val views = mutableStateMapOf<Long, ProjectView>()
    val backgroundAgents = BackgroundAgentsState(scope, backend.backgroundAgents::list) { notifications.reportError(it) }

    val prefs = UiPreferences(settingsStore, scope, backend.sound, backend.usageAlerts, backend.usageDefaults) { notifications.reportError(it) }
    val environment = ClaudeEnvironment(backend, scope, prefs) { notifications.reportError(it) }
    val projects: ProjectsState = ProjectsState(
        backend, scope,
        hasRunningSessions = { live.of(it).isNotEmpty() },
        sessionIdsOf = { id -> (viewOf(id).openSessions + viewOf(id).openShells + live.of(id).map { it.astrolabeSessionId }).distinct() },
        closeSession = { projectId, id -> if (shells.owns(id)) shells.close(projectId, id) else sessions.close(projectId, id) },
        requestConfirm = { confirm = it },
        showProject = { navigate(Route.Project(it)) },
        onRemoved = { removed -> navigator.leaveProjectIf { it == removed } },
        onLoaded = { forgetRemovedProjects(it); gits.loadNew(it); prefs.retainFoldedGroups(it); web.retain(it); reviews.retain(it) },
        reportError = { title, message -> notifications.reportError(title, message) },
        reportActionError = { notifications.reportError(it) },
    )
    val notifications: NotificationsState = NotificationsState({ projects.nameOf(it) }) { windowActive }
    val sessions: SessionsState = SessionsState(
        backend, scope, { live }, { environment.sessionUsage[it] }, { teams.defaultTeamOf(it) },
        onOpened = ::showSessionTab,
        onClosed = { projectId, id -> views[projectId] = viewOf(projectId).closeSession(id) },
        showProject = { navigate(routeOf(it)) },
        requestConfirm = { confirm = it },
        reportError = notifications::reportError,
        projectPath = { projects.find(it)?.path },
    )
    val contextBreakdown = ContextBreakdownState({ target, force -> backend.contextBreakdown.measure(target.directory, target.claudeSessionId, force) }, scope, ::contextTargetOf)
    val contextAlerts = ContextAlerts()
    val assistant = AssistantState(
        scope, backend.terminal, { live }, backend.dataDir.resolve("assistant"),
        launch = { sessions.launchAssistant(it) }, show = { navigate(Route.Assistant) }, enqueue = { id, text -> queue.add(id, text) }, requestConfirm = { confirm = it },
        reportError = notifications::reportError,
    )
    val assistantStartup = AssistantStartupState(
        scope, { settings.assistantStartup }, { mode -> prefs.update { it.copy(assistantStartup = mode) } },
        blocked = { author.dialogOpen || author.startOfferPending || confirm != null || applyPreset.isOpen || presetOffer.offer != null },
        startInBackground = assistant::startInBackground, start = assistant::start,
    )
    val queue = QueueState(backend.sessions, backend.terminal, scope, sessions::isAttached, sessions::sendQueued, notifications::reportError)
    val usage = UsageState(backend.usageHistory, backend.sessionUsage, scope, visible = { route == Route.Usage }, reportError = notifications::reportError)
    val runs = RunState(
        backend.runConfigurations, backend.runs, backend.terminal, scope,
        projectPath = { projects.find(it)?.path },
        showRunTab = { select(it, ProjectTab.Run) },
        requestConfirm = { confirm = it },
        reportError = notifications::reportError,
    )
    val shells = ShellsState(
        backend, scope,
        openShellsOf = { viewOf(it).openShells },
        lastChosen = { settings.lastShell },
        onChosen = { name -> prefs.update { it.copy(lastShell = name) } },
        onOpened = { projectId, id -> views[projectId] = viewOf(projectId).openShell(id); navigate(Route.Project(projectId)) },
        onClosed = { projectId, id -> views[projectId]?.let { views[projectId] = it.closeShell(id) } },
        reportError = notifications::reportError,
    )
    val codeMode = CodeModeState(backend.codeModeExecutions, scope, notifications::reportError)
    val proposals = ProposalState(backend.proposals, scope, notifications::reportError, focusTerminal = { terminal.focus(it) }, onArrived = ::announceAssistantProposal)
    val answers = ProposalAnswers(proposals, assistant, { route }, ::viewOf, { live }, notifications::toast, ::openSession)
    val osNotifications = OsNotifications(
        scope, { settings }, { windowActive },
        isWaiting = { live.find(it)?.status == AgentStatus.NEEDS_ACTION },
        proposalOf = { proposals.of(it).firstOrNull() },
        projectName = { projects.nameOf(it) },
        show = notifications::popup,
    )
    var flashTaskbar: (NotificationKind) -> Unit = {}
    var trayAvailable by mutableStateOf(false)
    val waitingCount: Int get() = live.sessions.count { it.status == AgentStatus.NEEDS_ACTION }
    val teams = TeamsState(backend.teams, scope, { confirm = it }, notifications::reportError)
    val presets = PresetsState(backend.presets, backend.plugins, backend.skills, scope, { confirm = it }, notifications::reportError)
    private fun teamOptionsOf(projectId: Long) = TeamOptions(teams.teams, teams.defaultTeamOf(projectId))

    private fun recommendedPresetKeys(dir: Path): Set<String> = backend.presetRecommendation.recommendFor(dir, backend.bundledPresets.presets()).toSet()
    val applyPreset: ApplyPresetState = ApplyPresetState(
        backend.presetApply, backend.presetApplications, { presets.presets }, projects::find, scope, notifications::reportError,
        recommendedKeys = ::recommendedPresetKeys,
        startTidySession = { project, prompt, onStarted -> sessions.launchTask(project.id, teams.defaultTeamOf(project.id), prompt, onStarted = { onStarted() }) },
    )
    val presetOffer: PresetOfferState = PresetOfferState(
        backend.presetApplications, { presets.presets }, projects::find, scope,
        blocked = ::dialogBlocksOffer,
        review = { projectId, presetIds -> applyPreset.open(projectId, *presetIds.toLongArray()) },
        reportError = notifications::reportError,
        recommendedKeys = ::recommendedPresetKeys,
    )
    val prompts = PromptsState(backend.promptTemplates, scope, { confirm = it }, notifications::reportError, listOf(backend.projects::subscribe, backend.teams::subscribe))
    val sessionSearch = SessionSearchState(backend.sessionSearch, backend.transcripts, scope, { confirm = it }, notifications::reportError)
    val reviews = ReviewState(
        backend.review, { live }, { id, text -> queue.add(id, text) },
        { projectId, team, prompt, onNotStarted, onStarted -> sessions.launchTask(projectId, team, prompt, { onNotStarted() }, onStarted) },
        scope, notifications::reportError, ::teamOptionsOf,
    )
    val web = WebState(
        backend.web, webHostFactory, { id, text -> queue.add(id, text) },
        { projectId, team, prompt, onNotStarted, onStarted -> sessions.launchTask(projectId, team, prompt, { onNotStarted() }, onStarted) },
        { live }, scope, notifications::reportError, backend.review, { p, r -> reviews.select(p, r) }, { confirm = it },
        { JcefRuntime.availability() },
        prepareBrowser = { JcefRuntime.start(backend.dataDir) },
        teamOptions = ::teamOptionsOf,
    ).also {
        it.history = settings.webHistory
        it.onHistoryChange = prefs::setWebHistory
    }
    val extensions = ExtensionStates(backend, scope, { confirm = it }, notifications, environment)
    val author = AuthorProfileState(
        backend.author, scope,
        mcp = extensions.mcp,
        reportError = notifications::reportError,
        toast = notifications::toast,
    )
    val settingsDialog = SettingsState()
    val voice: VoiceSettingsState? = voiceSettingsOf(backend.voice, scope, { settings }, prefs::update, notifications::reportError)
    val codeModeSettings = CodeModeSettingsState(backend.codeMode, scope) { notifications.reportError(it) }
    val trust = TrustState(backend.trust, scope) { notifications.reportError(it) }
    val codeModeChip = CodeModeChipState(
        backend.projectCodeMode != null,
        { ProjectCodeModeState(backend.projectCodeMode, it.id, scope, { backend.pluginMcpServers?.servers(it.path) ?: emptyList() }, it.group, { backend.memberCodeModeServers(it) }, notifications::reportError) },
        { McpState(backend.mcp, it.path, scope, { request -> confirm = request }, notifications::toast) },
    )
    val tools: ToolsState = ToolsState(backend.toolchain, scope, { lspSetup.running }, notifications::reportError)
    val detectedTech = DetectedTechState(backend.toolchain?.health, scope)
    val lspSetup: LspSetupState = LspSetupState(backend.toolchain?.lspSetup, scope, { tools.install != InstallFlow.Idle || tools.installRunning }, detectedTech::refresh, notifications::reportError)
    val update = UpdateState(backend.updates, scope, notifications::reportError, ::requestUpdateInstall) { exitApp() }
    var exitApp: () -> Unit = {}
    val agents = AgentsState(backend.transcripts, scope, { live }, ::showSessionTerminal)
    private val unsubscribers = mutableListOf<Runnable>()
    val worktrees: WorktreeActions = WorktreeActions(projects, sessions, shells, runs, backend.git.taskWorktrees, backend.terminal) { gits }
    val gits = ProjectGits(
        backend.git, scope,
        GitHost(
            { settings.git }, { change -> prefs.update { it.copy(git = change(it.git)) } }, notifications::toast,
            worktrees::showWorktree, worktrees::forget, worktrees::stopWork, worktrees::hasRunningWork, worktrees::workStopping, environment::openDirectory,
            backend.runConfigurations::deleteByDirectory, { dir -> backend.runConfigurations.listByDirectory(dir).map { it.name() } },
            worktrees::forgetStored,
        ),
        projects::find,
        backend.git.taskWorktrees::forProject,
        { sessions.sessionDirectories },
    )
    val tasks = TaskDialogState(sessions, teams, queue, scope, backend.git.taskWorktrees, notifications::reportError, gits::branchOf, { projectId, sessionId -> gits.branchOf(projectId, sessionId) }, gits::taskRowsChanged, TaskImages(backend.dataDir.resolve("task-images"), backend.taskAttachments).also { scope.launch(kotlinx.coroutines.Dispatchers.IO) { it.deleteExpired() } })
    private val projectReload = Refresher(scope) { guarded { projects.load() } }
    private val navigator = Navigator(
        { live }, ::showSessionTab, ::onNavigated, { answers.answer(it) }, { answers.answerNextWaiting() }, { notifications.dismissToast(it) },
    )
    private val liveSync = LiveSessionsSync(
        scope, backend.sessions::sessions, { projects.all }, { live }, { live = it }, ::afterSessionsLoaded,
        onTick = { now = it }, windowActive = { windowActive },
        sweepUnopenedGits = { refocused ->
            gits.reloadUnopened((route as? Route.Project)?.id, afterRunningSweep = refocused) { refocused || live.of(it).isNotEmpty() }
        },
        onError = notifications::reportError,
    )

    fun updateSettings(transform: (UiSettings) -> UiSettings) = prefs.update(transform)

    fun viewOf(projectId: Long): ProjectView = views[projectId] ?: ProjectView()

    fun start() {
        unsubscribers += backend.projects.subscribe { projectReload.request() }
        unsubscribers += backend.sessions.subscribe { liveSync.request() }
        unsubscribers += backend.notifications.subscribe { notification ->
            scope.launch {
                notifications.receive(notification)
                osNotifications.offer(notification)
                flashTaskbar(notification.kind)
            }
        }
        backend.appControl?.let { unsubscribers += it.connect(AppControlAdapter(this, scope, backend.appControlProperties.uiTimeout())) }
        scope.launch { snapshotFlow { environment.sessionUsage }.collect(contextAlerts::rearmBelowCritical) }
        teams.start()
        presets.start()
        prompts.start()
        sessionSearch.start()
        reviews.start()
        queue.start()
        codeMode.start()
        author.offerAtStart()
        runs.start()
        usage.start()
        shells.detect()
        proposals.start()
        update.start()
        projectReload.request()
        liveSync.start()
        unsubscribers += environment.start { installation ->
            assistantStartup.atStart(installation?.let { it.installed && it.login != LoginState.LOGGED_OUT } == true)
        }
        scope.launch { guarded { notifications.restore(io { backend.notifications.history() }) } }
    }

    fun dispose() {
        extensions.dispose()
        teams.dispose()
        presets.dispose()
        prompts.dispose()
        sessionSearch.dispose()
        reviews.dispose()
        web.close()
        queue.dispose()
        codeMode.dispose()
        voice?.let { Thread(it::close, "voice-close").apply { isDaemon = true }.start() }
        runs.dispose()
        usage.dispose()
        proposals.dispose()
        unsubscribers.forEach { it.run() }
        unsubscribers.clear()
    }

    private fun announceAssistantProposal(proposal: Proposal) {
        if (route != Route.Assistant && proposal.astrolabeSessionId() == assistant.liveSessionId) {
            notifications.needsAnswer(ClaudeLauncher.ASSISTANT_PROJECT_ID, proposal.astrolabeSessionId(), "Assistant: needs your answer", proposalHeadline(proposal))
        }
    }

    private fun contextTargetOf(astrolabeSessionId: String): ContextTarget? {
        val session = live.find(astrolabeSessionId) ?: return null
        val directory = sessions.sessionDirectories[astrolabeSessionId] ?: projects.find(session.projectId())?.path ?: return null
        return ContextTarget(directory, session.claudeSessionId())
    }

    /** /compact is a model call that rewrites the conversation, so it is confirmed first. */
    fun requestCompactContext(astrolabeSessionId: String) {
        confirm = ConfirmRequest("Compact this session?", "Types /compact into the session. Claude summarises the conversation, which uses the model.", "Compact", destructive = false) { compactContext(astrolabeSessionId) }
    }

    /** Types /compact into the session; refused with the usual reason while it is busy. */
    fun compactContext(astrolabeSessionId: String) {
        sessions.send(astrolabeSessionId, COMPACT_COMMAND + Keys.ENTER) { contextAlerts.dismiss(astrolabeSessionId) }
    }

    private fun dialogBlocksOffer(): Boolean = author.dialogOpen || author.startOfferPending || assistantStartup.asking || confirm != null || settingsDialog.open ||
        applyPreset.isOpen || presetOffer.offer != null || presets.dialog != null || answers.dialog != null || tasks.projectId != null

    fun navigate(target: Route): Unit = navigator.navigate(target)

    private fun onNavigated(target: Route) {
        (target as? Route.Project)?.let { presetOffer.offerFor(it.id) }
        notificationsOpen = false
        usageOpen = false
        codeModeChip.open = false
        contextSessionId = null
        contextShowDetails = false
        modelPickerOpen = false
    }

    fun openSessions(tab: SessionsTab): Unit = navigator.openSessions(tab)

    fun selectSessionsTab(tab: SessionsTab): Unit = navigator.selectSessionsTab(tab)

    fun openSession(astrolabeSessionId: String): Unit = navigator.openSession(astrolabeSessionId)

    private fun showSessionTab(projectId: Long, astrolabeSessionId: String) {
        views[projectId] = viewOf(projectId).openSession(astrolabeSessionId)
    }

    private fun routeOf(projectId: Long): Route = navigator.routeOf(projectId)

    fun select(projectId: Long, tab: ProjectTab) {
        if (tab == ProjectTab.Web && projects.find(projectId)?.group == true) return
        views[projectId] = viewOf(projectId).select(tab)
    }


    /** Opens the project's Git tab, on [view] when given, and returns its state. */
    fun openGit(projectId: Long, view: GitSubView? = null): GitState? {
        select(projectId, ProjectTab.Git)
        return gits.of(projectId)?.also { git -> view?.let(git::show) }
    }

    fun openNotification(target: AppNotification): Unit = navigator.openNotification(target)

    fun openToast(toast: ToastItem): Unit = navigator.openToast(toast)

    /** Opens the folder picker; the chosen directory is handed to [ProjectsState.addAt]. */
    fun requestAddProject() {
        paletteOpen = false
        projects.pickerOpen = true
    }

    private fun showSessionTerminal(astrolabeSessionId: String) {
        openSession(astrolabeSessionId)
        backend.terminal.focus(astrolabeSessionId)
    }

    fun refreshBackgroundAgents(projectId: Long) {
        projects.find(projectId)?.path?.let { backgroundAgents.refresh(projectId, it) }
    }

    val terminal: TerminalPort get() = backend.terminal

    val dataDir: Path get() = backend.dataDir

    suspend fun activityOf(astrolabeSessionId: String): List<ActivityEntry> =
        try {
            io { backend.hooks.recent(astrolabeSessionId, ACTIVITY_LIMIT) }
        } catch (e: RuntimeException) {
            log.warn("Could not read the activity of session {}: {}", astrolabeSessionId, e.javaClass.simpleName)
            emptyList()
        }

    fun requestQuit(quit: () -> Unit) {
        val warning = quitWarningOf(live.sessions.size, tools.installRunning) ?: return quit()
        confirm = ConfirmRequest(
            title = "Quit Astrolabe Studio",
            message = warning,
            confirmLabel = "Quit",
            onConfirm = quit,
        )
    }

    /** Always asks, with [message] naming the update, because installing quits the app and closes every session; a running tool install is warned about like on quit. */
    fun requestUpdateInstall(message: String, install: () -> Unit, cancel: () -> Unit) {
        val full = listOfNotNull(message, quitWarningOf(0, tools.installRunning)).joinToString(" ")
        confirm = supersede(confirm, ConfirmRequest(title = "Install update", message = full, confirmLabel = "Install and restart", destructive = false, onConfirm = install, onCancel = cancel))
    }

    private fun forgetRemovedProjects(projectIds: Set<Long>) {
        views.keys.retainAll(projectIds)
        gits.retain(projectIds)
        backgroundAgents.retain(projectIds)
        runs.retainProjects(projectIds)
        navigator.leaveProjectIf { it !in projectIds }
        liveSync.request()
    }

    private fun afterSessionsLoaded(previous: Map<String, AgentStatus>, sessions: List<SessionSnapshot>) {
        agents.prune(sessions.map { it.astrolabeSessionId })
        notifications.dismissResolvedAttention(previous, sessions)
        projectsBecameIdle(previous, sessions).forEach { gits.refresh(it) }
        if (gits.anyReviewTurnEnded(previous, sessions) { id -> queue.of(id).map(QueuedPrompt::id) }) {
            notifications.toast(NotificationKind.INFO, "Agent finished", "Re-check review comments")
        }
    }

    private suspend fun guarded(onError: (String) -> Unit = notifications::reportError, block: suspend () -> Unit) = runGuarded(onError, block)
}
