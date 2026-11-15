package com.github.krpkbhdn.ccmanager.ui.tools

import com.github.krpkbhdn.ccmanager.toolchain.LspHealthMonitor
import com.github.krpkbhdn.ccmanager.toolchain.LspSetupService
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolsService
import com.github.krpkbhdn.ccmanager.toolchain.ToolCatalog
import com.github.krpkbhdn.ccmanager.toolchain.ToolDefinition
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallResult
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.Outcome
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolProbe
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.toolchain.ToolStatus
import com.github.krpkbhdn.ccmanager.ui.await
import com.github.krpkbhdn.ccmanager.toolchain.LauncherSpec
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ToolsStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errors = CopyOnWriteArrayList<String>()
    private val tool = ToolDefinition("a", "A", "G", ToolKind.LANGUAGE_SERVER, "a", listOf(), "", "", listOf(), mapOf(), "", mapOf(), mapOf())
    private val catalog = mock(ToolCatalog::class.java).also { `when`(it.all()).thenReturn(listOf(tool)) }
    private val probe = mock(ToolProbe::class.java)
    private val installer = mock(ToolInstallService::class.java)

    @AfterEach
    fun stop() = scope.cancel()

    private fun status(state: ToolState) = ToolStatus("a", state, "", "")

    private fun plan(blocker: String? = null) = InstallPlan(tool, listOf("npm", "i", "a"), "npm i a", Optional.ofNullable(blocker), "", Optional.empty())

    private fun state(blocker: String? = null): ToolsState {
        `when`(installer.plan(anyString())).thenAnswer { Optional.of(plan(blocker)) }
        return ToolsState(ToolchainBackend(catalog, probe, installer, mock(ProjectToolsService::class.java), mock(LspSetupService::class.java), mock(LspHealthMonitor::class.java)), scope) { title, _ -> errors += title }
    }

    private fun ToolsState.loaded(): ToolsState {
        loadPlans()
        await { plans.containsKey("a") }
        return this
    }

    @Test
    fun `a full check that started before an install and ends after it does not overwrite the installed status`() {
        val release = CountDownLatch(1)
        val probing = CountDownLatch(1)
        `when`(probe.probeAllFresh()).thenAnswer {
            probing.countDown()
            release.await()
            listOf(status(ToolState.MISSING))
        }
        `when`(installer.install(anyString(), any())).thenReturn(InstallResult(Outcome.INSTALLED, 0, status(ToolState.INSTALLED), "Installed a"))
        val tools = state().loaded()

        tools.checkAll()
        probing.await()
        tools.requestInstall("a")
        tools.confirmInstall()
        await { tools.install is InstallFlow.Done }
        assertThat(tools.statuses["a"]?.state).isEqualTo(ToolState.INSTALLED)
        release.countDown()
        await { !tools.checkingAll }

        assertThat(tools.statuses["a"]?.state).isEqualTo(ToolState.INSTALLED)
    }

    @Test
    fun `a full check started after the install is accepted`() {
        `when`(probe.probeAllFresh()).thenReturn(listOf(status(ToolState.MISSING)))
        `when`(installer.install(anyString(), any())).thenReturn(InstallResult(Outcome.INSTALLED, 0, status(ToolState.INSTALLED), "Installed a"))
        val tools = state().loaded()
        tools.requestInstall("a")
        tools.confirmInstall()
        await { tools.install is InstallFlow.Done }
        assertThat(tools.statuses["a"]?.state).isEqualTo(ToolState.INSTALLED)

        tools.checkAll()
        await { tools.statuses["a"]?.state == ToolState.MISSING }
    }

    @Test
    fun `an install walks through confirm, running and done and records the new status`() {
        val release = CountDownLatch(1)
        `when`(installer.install(anyString(), any())).thenAnswer {
            release.await()
            InstallResult(Outcome.INSTALLED, 0, status(ToolState.INSTALLED), "Installed a")
        }
        val tools = state().loaded()
        assertThat(tools.install).isEqualTo(InstallFlow.Idle)

        tools.requestInstall("a")
        assertThat(tools.install).isInstanceOf(InstallFlow.Confirm::class.java)

        tools.confirmInstall()
        assertThat(tools.install).isInstanceOf(InstallFlow.Running::class.java)
        assertThat(tools.installRunning).isTrue()
        tools.closeInstall()
        assertThat(tools.install).isInstanceOf(InstallFlow.Running::class.java)

        release.countDown()
        await { tools.install is InstallFlow.Done }
        assertThat((tools.install as InstallFlow.Done).result.outcome()).isEqualTo(Outcome.INSTALLED)
        await { tools.statuses["a"]?.state == ToolState.INSTALLED }
        tools.closeInstall()
        assertThat(tools.install).isEqualTo(InstallFlow.Idle)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `cancelling a running install asks the installer to stop and ends in a failed result, not stuck busy`() {
        val release = CountDownLatch(1)
        `when`(installer.install(anyString(), any())).thenAnswer {
            release.await()
            InstallResult(Outcome.FAILED, 1, status(ToolState.MISSING), "Cancelled")
        }
        `when`(installer.cancel()).thenAnswer { release.countDown() }
        val tools = state().loaded()
        tools.requestInstall("a")
        tools.confirmInstall()
        assertThat(tools.installRunning).isTrue()

        tools.cancelInstall()

        await { tools.install is InstallFlow.Done }
        assertThat((tools.install as InstallFlow.Done).result.outcome()).isEqualTo(Outcome.FAILED)
        assertThat(tools.installRunning).isFalse()
        tools.closeInstall()
        assertThat(tools.install).isEqualTo(InstallFlow.Idle)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `a blocked plan cannot be confirmed`() {
        val tools = state(blocker = "Install Node first").loaded()

        tools.requestInstall("a")

        assertThat(tools.install).isEqualTo(InstallFlow.Idle)
    }

    @Test
    fun `a plan without an automatic command opens the manual dialog and never runs`() {
        `when`(installer.plan(anyString())).thenAnswer { Optional.of(InstallPlan(tool, listOf(), "", Optional.empty(), "brew install a", Optional.empty())) }
        val tools = ToolsState(ToolchainBackend(catalog, probe, installer, mock(ProjectToolsService::class.java), mock(LspSetupService::class.java), mock(LspHealthMonitor::class.java)), scope) { title, _ -> errors += title }.loaded()

        tools.requestInstall("a")

        assertThat(tools.install).isInstanceOf(InstallFlow.Manual::class.java)
        tools.confirmInstall()
        assertThat(tools.install).isInstanceOf(InstallFlow.Manual::class.java)
    }

    @Test
    fun `a download plan opens the confirm step`() {
        val download = ToolInstallService.DownloadPlan("h", "https://h/latest", "https://h/{version}.zip", Path.of("tools", "a"), LauncherSpec(LauncherSpec.Kind.BINARY, "bin/a"))
        `when`(installer.plan(anyString())).thenAnswer { Optional.of(InstallPlan(tool, listOf(), "", Optional.empty(), "", Optional.of(download))) }
        val tools = ToolsState(ToolchainBackend(catalog, probe, installer, mock(ProjectToolsService::class.java), mock(LspSetupService::class.java), mock(LspHealthMonitor::class.java)), scope) { title, _ -> errors += title }.loaded()

        tools.requestInstall("a")

        assertThat(tools.install).isInstanceOf(InstallFlow.Confirm::class.java)
    }

    @Test
    fun `uninstall is offered only for a managed tool and ends in removed with the status recorded`() {
        `when`(installer.isManaged("a")).thenReturn(true)
        `when`(installer.uninstall("a")).thenReturn(InstallResult(Outcome.INSTALLED, 0, status(ToolState.MISSING), "Removed A"))
        val tools = state().loaded()
        await { tools.isManaged("a") }

        tools.requestUninstall("a")
        assertThat(tools.install).isInstanceOf(InstallFlow.ConfirmRemove::class.java)
        tools.confirmUninstall()

        await { tools.install is InstallFlow.Removed }
        assertThat((tools.install as InstallFlow.Removed).result.message()).isEqualTo("Removed A")
        await { tools.statuses["a"]?.state == ToolState.MISSING }
        tools.closeInstall()
        assertThat(tools.install).isEqualTo(InstallFlow.Idle)
    }

    @Test
    fun `an unmanaged tool cannot be uninstalled`() {
        val tools = state().loaded()

        tools.requestUninstall("a")

        assertThat(tools.install).isEqualTo(InstallFlow.Idle)
    }

    @Test
    fun `a blocked install result is shown but leaves the status alone`() {
        `when`(installer.install(anyString(), any())).thenReturn(InstallResult(Outcome.BLOCKED, 0, status(ToolState.INSTALLED), "Install Node first"))
        val tools = state().loaded()

        tools.requestInstall("a")
        tools.confirmInstall()

        await { tools.install is InstallFlow.Done }
        assertThat(tools.statuses).doesNotContainKey("a")
    }

    @Test
    fun `install and uninstall requests are refused while the blocked lambda is true`() {
        `when`(installer.plan(anyString())).thenAnswer { Optional.of(plan()) }
        `when`(installer.isManaged(anyString())).thenReturn(true)
        var blocked = true
        val tools = ToolsState(ToolchainBackend(catalog, probe, installer, mock(ProjectToolsService::class.java), mock(LspSetupService::class.java), mock(LspHealthMonitor::class.java)), scope, { blocked }) { title, _ -> errors += title }.loaded()

        tools.requestInstall("a")
        tools.requestUninstall("a")
        assertThat(tools.install).isEqualTo(InstallFlow.Idle)

        blocked = false
        tools.requestInstall("a")
        assertThat(tools.install).isInstanceOf(InstallFlow.Confirm::class.java)
    }

    @Test
    fun `confirming an install is refused when a setup took over after the request`() {
        `when`(installer.plan(anyString())).thenAnswer { Optional.of(plan()) }
        var blocked = false
        val tools = ToolsState(ToolchainBackend(catalog, probe, installer, mock(ProjectToolsService::class.java), mock(LspSetupService::class.java), mock(LspHealthMonitor::class.java)), scope, { blocked }) { title, _ -> errors += title }.loaded()
        tools.requestInstall("a")

        blocked = true
        tools.confirmInstall()

        assertThat(tools.install).isInstanceOf(InstallFlow.Confirm::class.java)
        org.mockito.Mockito.verify(installer, org.mockito.Mockito.never()).install(anyString(), any())
    }
}
