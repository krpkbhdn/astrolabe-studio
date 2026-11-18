package com.github.krpkbhdn.astrolabe.ui.tools

import com.github.krpkbhdn.astrolabe.toolchain.ArchiveKind
import com.github.krpkbhdn.astrolabe.toolchain.DownloadSource
import com.github.krpkbhdn.astrolabe.toolchain.LatestSource
import com.github.krpkbhdn.astrolabe.toolchain.PinnedVersion
import com.github.krpkbhdn.astrolabe.toolchain.ToolDefinition
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.InstallResult
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.Outcome
import com.github.krpkbhdn.astrolabe.toolchain.ToolKind
import com.github.krpkbhdn.astrolabe.toolchain.ToolState
import com.github.krpkbhdn.astrolabe.toolchain.ToolStatus
import com.github.krpkbhdn.astrolabe.toolchain.LauncherSpec
import com.github.krpkbhdn.astrolabe.toolchain.ToolInstallService.DownloadPlan
import java.nio.file.Path
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ToolsModelTest {
    private fun tool(id: String, kind: ToolKind, group: String, prerequisite: String = "") =
        ToolDefinition(id, id.uppercase(), group, kind, id, listOf("--version"), "", prerequisite, listOf(), mapOf(), "https://docs/$id", mapOf(), mapOf())

    private fun status(id: String, state: ToolState, version: String = "", detail: String = "") = ToolStatus(id, state, version, detail)

    private fun plan(tool: ToolDefinition, command: List<String> = listOf("npm", "i"), blocker: String? = null) =
        InstallPlan(tool, command, command.joinToString(" "), Optional.ofNullable(blocker), "", Optional.empty())

    @Test
    fun `includes note lists the covered technologies and is absent without any`() {
        val plain = tool("ts", ToolKind.LANGUAGE_SERVER, "TypeScript")
        val withReact = ToolDefinition("ts", "TS", "TypeScript", ToolKind.LANGUAGE_SERVER, "ts", listOf(), "", "", listOf(), mapOf(), "", mapOf(), mapOf(), listOf("React"))

        assertThat(includesNoteOf(plain)).isNull()
        assertThat(includesNoteOf(withReact)).isEqualTo("Includes React")
    }

    @Test
    fun `groups put prerequisites first and keep catalog order`() {
        val node = tool("node", ToolKind.PREREQUISITE, "Runtimes")
        val ts = tool("ts", ToolKind.LANGUAGE_SERVER, "TypeScript")
        val eslint = tool("eslint", ToolKind.LANGUAGE_SERVER, "TypeScript")
        val go = tool("gopls", ToolKind.LANGUAGE_SERVER, "Go")

        val groups = groupTools(listOf(ts, eslint, go, node), emptyMap())

        assertThat(groups.map { it.title }).containsExactly(PREREQUISITES_GROUP, "TypeScript", "Go")
        assertThat(groups[1].rows.map { it.tool.id }).containsExactly("ts", "eslint")
    }

    @Test
    fun `status chip names the state and carries the version and detail`() {
        assertThat(statusChipOf(status("a", ToolState.INSTALLED, "5.4"), false)).isEqualTo(StatusChipView("Installed 5.4", ChipTone.SUCCESS, null))
        assertThat(statusChipOf(status("a", ToolState.FAILED, detail = "boom"), false)).isEqualTo(StatusChipView("Failed", ChipTone.DANGER, "boom"))
        assertThat(statusChipOf(status("a", ToolState.MISSING), false).tone).isEqualTo(ChipTone.WARNING)
        assertThat(statusChipOf(null, false).text).isEqualTo("Not checked")
        assertThat(statusChipOf(null, true).text).isEqualTo("Checking...")
    }

    @Test
    fun `install button follows the plan`() {
        val t = tool("a", ToolKind.LANGUAGE_SERVER, "G")
        val missing = status("a", ToolState.MISSING)

        assertThat(installButtonOf(status("a", ToolState.INSTALLED), plan(t), t.docsUrl)).isEqualTo(InstallButton.Hidden)
        assertThat(installButtonOf(missing, plan(t), t.docsUrl)).isEqualTo(InstallButton.Install(true, null))
        assertThat(installButtonOf(missing, plan(t, blocker = "Install NODE first"), t.docsUrl)).isEqualTo(InstallButton.Install(false, "Install NODE first"))
        assertThat(installButtonOf(missing, plan(t, command = listOf()), t.docsUrl)).isEqualTo(InstallButton.Install(true, null, "https://docs/a"))
        assertThat(installButtonOf(missing, plan(t, command = listOf()), "")).isEqualTo(InstallButton.Install(true, null, null))
        assertThat(installButtonOf(null, null, t.docsUrl)).isEqualTo(InstallButton.Hidden)
    }

    private val download = DownloadPlan(
        "download.eclipse.org", "https://download.eclipse.org/x/latest.txt", "https://download.eclipse.org/x/x-{version}.tar.gz",
        Path.of("tools", "a"), LauncherSpec(LauncherSpec.Kind.BINARY, "bin/a"),
    )

    private fun downloadPlan(t: ToolDefinition, blocker: String? = null) =
        InstallPlan(t, listOf(), "", Optional.ofNullable(blocker), "", Optional.of(download))

    @Test
    fun `a download plan without a command is installable, not manual`() {
        val t = tool("a", ToolKind.LANGUAGE_SERVER, "G")
        val missing = status("a", ToolState.MISSING)

        assertThat(installButtonOf(missing, downloadPlan(t), t.docsUrl)).isEqualTo(InstallButton.Install(true, null))
        assertThat(installButtonOf(missing, downloadPlan(t, "Install JAVA first"), t.docsUrl)).isEqualTo(InstallButton.Install(false, "Install JAVA first"))
    }

    @Test
    fun `a managed tool offers update and uninstall even when present`() {
        val t = tool("a", ToolKind.LANGUAGE_SERVER, "G")
        val present = status("a", ToolState.RESPONDING)

        assertThat(installButtonOf(present, downloadPlan(t), t.docsUrl, managed = true)).isEqualTo(InstallButton.Manage(true, null))
        assertThat(installButtonOf(present, downloadPlan(t, "Install JAVA first"), t.docsUrl, managed = true)).isEqualTo(InstallButton.Manage(false, "Install JAVA first"))
    }

    @Test
    fun `download facts name the host, urls, integrity, folder and launcher and a command plan has none`() {
        val t = tool("a", ToolKind.LANGUAGE_SERVER, "G")

        val facts = downloadFactsOf(downloadPlan(t)).associate { it.label to it.value }

        assertThat(facts.keys).containsExactly("Source host", "Latest version", "Archive", "Integrity", "Installs to", "Launcher")
        assertThat(facts["Source host"]).isEqualTo("download.eclipse.org")
        assertThat(facts["Archive"]).contains("x-{version}.tar.gz").contains("latest version")
        assertThat(facts["Integrity"]).contains("same host").contains("corruption")
        assertThat(facts["Launcher"]).isEqualTo("a (runs bin/a)")
        assertThat(downloadFactsOf(plan(t))).isEmpty()
    }

    private fun channelTool(): ToolDefinition {
        val source = DownloadSource(
            LatestSource(LatestSource.Kind.TEXT_FILE, "https://h/latest.txt", "(.*)"), "https://h/x-{version}.tar.gz", ArchiveKind.TAR_GZ,
            LauncherSpec(LauncherSpec.Kind.JDTLS, ""), PinnedVersion("1.2.3", "https://h/x-1.2.3.tar.gz", "abc123"),
        )
        return ToolDefinition("jdtls", "Java", "Java", ToolKind.LANGUAGE_SERVER, "jdtls", listOf(), "", "", listOf(), mapOf(), "", mapOf(), mapOf("windows" to source))
    }

    private fun channelPlan(t: ToolDefinition) = InstallPlan(t, listOf(), "", Optional.empty(), "", Optional.of(download))

    @Test
    fun `only a tool with a pinned version shows the channel picker`() {
        assertThat(showsChannelPicker(channelTool())).isTrue()
        assertThat(showsChannelPicker(tool("a", ToolKind.LANGUAGE_SERVER, "G"))).isFalse()
    }

    @Test
    fun `channel items label verified and latest with their detail`() {
        assertThat(CHANNEL_ITEMS.map { it.label to it.detail }).containsExactly(
            "Verified" to "recommended, tested with astrolabe",
            "Latest" to "newest upstream, not verified",
        )
        assertThat(channelItemOf(ToolChannel.LATEST).label).isEqualTo("Latest")
    }

    @Test
    fun `verified facts show the pinned version and catalog hash`() {
        val facts = downloadFactsOf(channelPlan(channelTool()), ToolChannel.VERIFIED).associate { it.label to it.value }

        assertThat(facts["Verified version"]).isEqualTo("Version 1.2.3, verified with the output-redirect bundle, SHA-256 from the catalog abc123")
        assertThat(facts["Archive"]).isEqualTo("https://h/x-1.2.3.tar.gz")
    }

    @Test
    fun `latest facts say the version is resolved at install and unverified`() {
        val facts = downloadFactsOf(channelPlan(channelTool()), ToolChannel.LATEST).associate { it.label to it.value }

        assertThat(facts["Latest version"]).contains("resolved at install").contains(".sha256").contains("not verified").contains("switch to Verified")
        assertThat(facts["Archive"]).contains("{version}")
    }

    @Test
    fun `a tool without channels keeps the plain facts for any channel`() {
        val t = tool("a", ToolKind.LANGUAGE_SERVER, "G")

        assertThat(downloadFactsOf(downloadPlan(t), ToolChannel.LATEST)).isEqualTo(downloadFactsOf(downloadPlan(t)))
    }

    @Test
    fun `use verified version is offered only for a failed latest install`() {
        val t = channelTool()
        val failed = status("jdtls", ToolState.FAILED)

        assertThat(offersVerifiedVersion(t, ToolChannel.LATEST, failed)).isTrue()
        assertThat(offersVerifiedVersion(t, ToolChannel.VERIFIED, failed)).isFalse()
        assertThat(offersVerifiedVersion(t, null, failed)).isFalse()
        assertThat(offersVerifiedVersion(t, ToolChannel.LATEST, status("jdtls", ToolState.RESPONDING))).isFalse()
        assertThat(offersVerifiedVersion(tool("a", ToolKind.LANGUAGE_SERVER, "G"), ToolChannel.LATEST, failed)).isFalse()
    }

    @Test
    fun `the path hint folder is the bin directory beside the tool folders`() {
        assertThat(binDirectoryOf(download)).isEqualTo(Path.of("tools", "bin").toString())
    }

    @Test
    fun `manual install view prefers the command and keeps the download page`() {
        assertThat(manualInstallOf("brew install a", "https://d")).isEqualTo(ManualInstallView("This package cannot be installed automatically. Run this command in a terminal:", "brew install a", "https://d"))
        assertThat(manualInstallOf("brew install a", "")).isEqualTo(ManualInstallView("This package cannot be installed automatically. Run this command in a terminal:", "brew install a", null))
        assertThat(manualInstallOf("", "https://d")).isEqualTo(ManualInstallView("No command is known for this system. Use the download page:", null, "https://d"))
        assertThat(manualInstallOf(" ", "")).isEqualTo(ManualInstallView("No command is known for this system.", null, null))
    }

    @Test
    fun `log follows the end unless the user scrolled up`() {
        assertThat(keepsFollowing(1000, 1000)).isTrue()
        assertThat(keepsFollowing(997, 1000)).isTrue()
        assertThat(keepsFollowing(500, 1000)).isFalse()
        assertThat(keepsFollowing(0, 0)).isTrue()
    }

    @Test
    fun `prerequisite note names the needed tool`() {
        val t = tool("ts", ToolKind.LANGUAGE_SERVER, "G", prerequisite = "node")
        assertThat(prerequisiteNote(t) { "Node.js" }).isEqualTo("Needs Node.js")
        assertThat(prerequisiteNote(tool("x", ToolKind.PREREQUISITE, "G")) { null }).isNull()
    }

    @Test
    fun `log keeps only the last lines of the whole output`() {
        val output = (1..600).joinToString("\n") { "line $it" }
        val lines = logLinesOf(output)
        assertThat(lines).hasSize(LOG_LINE_LIMIT)
        assertThat(lines.last()).isEqualTo("line 600")
    }

    @Test
    fun `result message keeps the service text when the tool is still missing`() {
        val s = status("a", ToolState.MISSING)
        assertThat(resultMessageOf(InstallResult(Outcome.STILL_MISSING, 0, s, "Not on PATH."))).isEqualTo("Not on PATH.")
        assertThat(resultMessageOf(InstallResult(Outcome.FAILED, 2, s, "oops"))).contains("exit code 2").contains("oops")
    }
}
