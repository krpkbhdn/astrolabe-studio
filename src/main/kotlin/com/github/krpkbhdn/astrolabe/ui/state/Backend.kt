package com.github.krpkbhdn.astrolabe.ui.state

import java.time.Duration
import com.github.krpkbhdn.astrolabe.app.AppControlProperties
import com.github.krpkbhdn.astrolabe.app.AppControlRegistry
import com.github.krpkbhdn.astrolabe.author.AuthorCatalogService
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService
import com.github.krpkbhdn.astrolabe.extension.mcp.PluginMcpServers
import com.github.krpkbhdn.astrolabe.codemode.policy.ExecutionRegistry
import com.github.krpkbhdn.astrolabe.claude.hook.HookEventStore
import com.github.krpkbhdn.astrolabe.claude.hook.HookHelperCommand
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService
import com.github.krpkbhdn.astrolabe.claude.launch.BackgroundAgentService
import com.github.krpkbhdn.astrolabe.claude.launch.ClaudeLauncher
import com.github.krpkbhdn.astrolabe.app.mcp.AssistantMcpTool
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalService
import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationSettingsService
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector
import com.github.krpkbhdn.astrolabe.ui.settings.CodeModeBackend
import com.github.krpkbhdn.astrolabe.claude.session.SessionProperties
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry
import com.github.krpkbhdn.astrolabe.config.AppProperties
import com.github.krpkbhdn.astrolabe.claude.session.TranscriptService
import com.github.krpkbhdn.astrolabe.notification.NotificationService
import com.github.krpkbhdn.astrolabe.notification.NotificationSoundService
import com.github.krpkbhdn.astrolabe.history.SessionSearchService
import com.github.krpkbhdn.astrolabe.usage.UsageProperties
import com.github.krpkbhdn.astrolabe.usage.history.UsageAlertService
import com.github.krpkbhdn.astrolabe.usage.history.UsageHistoryRepository
import com.github.krpkbhdn.astrolabe.usage.history.UsageThresholds
import com.github.krpkbhdn.astrolabe.preset.PresetApplicationService
import com.github.krpkbhdn.astrolabe.preset.ProjectPresetService
import com.github.krpkbhdn.astrolabe.preset.BundledPresetLoader
import com.github.krpkbhdn.astrolabe.preset.PresetRecommendation
import com.github.krpkbhdn.astrolabe.preset.apply.PresetApplyService
import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.project.ProjectGroupService
import com.github.krpkbhdn.astrolabe.project.ProjectService
import com.github.krpkbhdn.astrolabe.project.TrustSettingsService
import com.github.krpkbhdn.astrolabe.prompt.PromptTemplateService
import com.github.krpkbhdn.astrolabe.run.RunConfigurationService
import com.github.krpkbhdn.astrolabe.review.ReviewService
import com.github.krpkbhdn.astrolabe.run.RunService
import com.github.krpkbhdn.astrolabe.web.WebReviewService
import com.github.krpkbhdn.astrolabe.extension.mcp.McpService
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService
import com.github.krpkbhdn.astrolabe.extension.skill.SkillService
import com.github.krpkbhdn.astrolabe.shell.ShellDetector
import com.github.krpkbhdn.astrolabe.shell.ShellLauncher
import com.github.krpkbhdn.astrolabe.taskattachment.TaskAttachmentProperties
import com.github.krpkbhdn.astrolabe.team.TeamService
import com.github.krpkbhdn.astrolabe.ui.git.model.GitBackend
import com.github.krpkbhdn.astrolabe.usage.RateLimitService
import com.github.krpkbhdn.astrolabe.update.UpdateService
import com.github.krpkbhdn.astrolabe.usage.SessionUsageService
import com.github.krpkbhdn.astrolabe.usage.context.ContextBreakdownService
import com.github.krpkbhdn.astrolabe.toolchain.GroupLspService
import com.github.krpkbhdn.astrolabe.toolchain.LspHealthMonitor
import com.github.krpkbhdn.astrolabe.toolchain.LspSetupService
import com.github.krpkbhdn.astrolabe.toolchain.ProjectToolsService
import com.github.krpkbhdn.astrolabe.toolchain.ToolCatalog
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService
import com.github.krpkbhdn.astrolabe.toolchain.ToolProbe
import com.github.krpkbhdn.astrolabe.ui.tools.ToolchainBackend
import com.github.krpkbhdn.astrolabe.ui.voice.VoiceBackend
import com.github.krpkbhdn.astrolabe.voice.VoiceInputService
import com.github.krpkbhdn.astrolabe.voice.asset.VoiceAssetService
import com.github.krpkbhdn.astrolabe.voice.audio.Microphone
import com.github.krpkbhdn.astrolabe.voice.device.DeviceService
import com.github.krpkbhdn.astrolabe.workspace.DirectoryOpener
import com.github.krpkbhdn.astrolabe.workspace.CustomIdeService
import com.github.krpkbhdn.astrolabe.workspace.IdeDetector
import com.github.krpkbhdn.astrolabe.workspace.IdeLauncher
import java.nio.file.Path
import org.springframework.context.ConfigurableApplicationContext

/** Every backend collaborator the UI talks to. */
class Backend(
    val projects: ProjectService,
    val groups: ProjectGroupService,
    val installation: ClaudeInstallationService,
    val sessions: SessionRegistry,
    val notifications: NotificationService,
    val launcher: ClaudeLauncher,
    val backgroundAgents: BackgroundAgentService,
    val hooks: HookEventStore,
    val directories: DirectoryOpener,
    val ide: IdeLauncher,
    val ideDetector: IdeDetector,
    val customIdes: CustomIdeService,
    val git: GitBackend,
    val rateLimits: RateLimitService,
    val sessionUsage: SessionUsageService,
    val teams: TeamService,
    val plugins: PluginService,
    val mcp: McpService,
    val skills: SkillService,
    val transcripts: TranscriptService,
    val runConfigurations: RunConfigurationService,
    val runs: RunService,
    val shellDetector: ShellDetector,
    val shellLauncher: ShellLauncher,
    val author: AuthorCatalogService,
    val proposals: ProposalService,
    val promptTemplates: PromptTemplateService,
    val sessionSearch: SessionSearchService,
    val presets: ProjectPresetService,
    val presetApply: PresetApplyService,
    val presetRecommendation: PresetRecommendation,
    val bundledPresets: BundledPresetLoader,
    val presetApplications: PresetApplicationService,
    val review: ReviewService,
    val web: WebReviewService,
    val terminal: TerminalPort = HostTerminalPort,
    val updates: UpdateService? = null,
    val sound: NotificationSoundService? = null,
    val usageHistory: UsageHistoryRepository? = null,
    val usageAlerts: UsageAlertService? = null,
    val usageDefaults: UsageThresholds = UsageThresholds(0, 0, 0.0),
    val dataDir: Path = Path.of(System.getProperty("user.home"), ".astrolabe-studio"),
    val sessionProperties: SessionProperties,
    val contextBreakdown: ContextBreakdownService,
    val appControl: AppControlRegistry? = null,
    val appControlProperties: AppControlProperties = AppControlProperties(Duration.ofSeconds(10), 20_000),
    val assistantTools: List<AssistantMcpTool> = emptyList(),
    val voice: VoiceBackend? = null,
    val hookHelper: HookHelperCommand? = null,
    val codeMode: CodeModeBackend? = null,
    val trust: TrustSettingsService? = null,
    val projectCodeMode: ProjectCodeModeService? = null,
    val pluginMcpServers: PluginMcpServers? = null,
    val codeModeExecutions: ExecutionRegistry? = null,
    val toolchain: ToolchainBackend? = null,
    val taskAttachments: TaskAttachmentProperties = TaskAttachmentProperties(10, 20L * 1024 * 1024, Duration.ofDays(7), 10, 1000),
) {
    /** Code Mode servers each member of [group] has saved, by member name, for the group's inherited view. */
    fun memberCodeModeServers(group: Project): List<Pair<String, List<String>>> =
        groups.members(group.id).map { it.name to (projectCodeMode?.get(it.id)?.servers() ?: emptyList()) }

    companion object {
        fun from(context: ConfigurableApplicationContext) = Backend(
            projects = context.getBean(ProjectService::class.java),
            groups = context.getBean(ProjectGroupService::class.java),
            installation = context.getBean(ClaudeInstallationService::class.java),
            sessions = context.getBean(SessionRegistry::class.java),
            notifications = context.getBean(NotificationService::class.java),
            launcher = context.getBean(ClaudeLauncher::class.java),
            backgroundAgents = context.getBean(BackgroundAgentService::class.java),
            hooks = context.getBean(HookEventStore::class.java),
            directories = context.getBean(DirectoryOpener::class.java),
            ide = context.getBean(IdeLauncher::class.java),
            ideDetector = context.getBean(IdeDetector::class.java),
            customIdes = context.getBean(CustomIdeService::class.java),
            git = GitBackend.from(context),
            rateLimits = context.getBean(RateLimitService::class.java),
            sessionUsage = context.getBean(SessionUsageService::class.java),
            teams = context.getBean(TeamService::class.java),
            plugins = context.getBean(PluginService::class.java),
            mcp = context.getBean(McpService::class.java),
            skills = context.getBean(SkillService::class.java),
            transcripts = context.getBean(TranscriptService::class.java),
            runConfigurations = context.getBean(RunConfigurationService::class.java),
            runs = context.getBean(RunService::class.java),
            shellDetector = context.getBean(ShellDetector::class.java),
            shellLauncher = context.getBean(ShellLauncher::class.java),
            author = context.getBean(AuthorCatalogService::class.java),
            proposals = context.getBean(ProposalService::class.java),
            promptTemplates = context.getBean(PromptTemplateService::class.java),
            sessionSearch = context.getBean(SessionSearchService::class.java),
            presets = context.getBean(ProjectPresetService::class.java),
            presetApply = context.getBean(PresetApplyService::class.java),
            presetRecommendation = context.getBean(PresetRecommendation::class.java),
            bundledPresets = context.getBean(BundledPresetLoader::class.java),
            presetApplications = context.getBean(PresetApplicationService::class.java),
            review = context.getBean(ReviewService::class.java),
            web = context.getBean(WebReviewService::class.java),
            updates = context.getBean(UpdateService::class.java),
            sound = context.getBean(NotificationSoundService::class.java),
            usageHistory = context.getBean(UsageHistoryRepository::class.java),
            usageAlerts = context.getBean(UsageAlertService::class.java),
            usageDefaults = context.getBean(UsageProperties::class.java).let { UsageThresholds(it.fiveHourPercent(), it.sevenDayPercent(), it.dailyBudgetUsd()) },
            dataDir = context.getBean(AppProperties::class.java).dataDir(),
            sessionProperties = context.getBean(SessionProperties::class.java),
            contextBreakdown = context.getBean(ContextBreakdownService::class.java),
            appControl = context.getBean(AppControlRegistry::class.java),
            appControlProperties = context.getBean(AppControlProperties::class.java),
            assistantTools = context.getBeansOfType(AssistantMcpTool::class.java).values.toList(),
            voice = VoiceBackend(
                context.getBean(VoiceInputService::class.java),
                context.getBean(DeviceService::class.java),
                context.getBean(VoiceAssetService::class.java),
                context.getBean(Microphone::class.java),
            ),
            hookHelper = context.getBean(HookHelperCommand::class.java),
            toolchain = ToolchainBackend(
                context.getBean(ToolCatalog::class.java),
                context.getBean(ToolProbe::class.java),
                context.getBean(ToolInstallService::class.java),
                context.getBean(ProjectToolsService::class.java),
                context.getBean(LspSetupService::class.java),
                context.getBean(LspHealthMonitor::class.java),
                context.getBean(GroupLspService::class.java),
            ),
            projectCodeMode = context.getBean(ProjectCodeModeService::class.java),
            pluginMcpServers = context.getBean(PluginMcpServers::class.java),
            codeModeExecutions = context.getBean(ExecutionRegistry::class.java),
            codeMode = CodeModeBackend(
                context.getBean(IsolationSettingsService::class.java),
                context.getBean(ContainerRuntimeDetector::class.java),
                context.getBean(CodeModeProperties::class.java).image(),
            ),
            trust = context.getBean(TrustSettingsService::class.java),
            taskAttachments = context.getBean(TaskAttachmentProperties::class.java),
        )
    }
}
