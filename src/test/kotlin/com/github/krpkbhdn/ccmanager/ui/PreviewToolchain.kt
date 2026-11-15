package com.github.krpkbhdn.ccmanager.ui

import com.github.krpkbhdn.ccmanager.toolchain.LauncherSpec
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolRow
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.DownloadPlan
import java.nio.file.Path
import com.github.krpkbhdn.ccmanager.toolchain.ProjectToolsService
import com.github.krpkbhdn.ccmanager.toolchain.ToolKind
import com.github.krpkbhdn.ccmanager.toolchain.ToolCatalog
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallPlan
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.InstallResult
import com.github.krpkbhdn.ccmanager.toolchain.ToolInstallService.Outcome
import com.github.krpkbhdn.ccmanager.toolchain.ToolProbe
import com.github.krpkbhdn.ccmanager.toolchain.ToolState
import com.github.krpkbhdn.ccmanager.toolchain.ToolStatus
import com.github.krpkbhdn.ccmanager.ui.tools.ToolchainBackend
import java.util.Optional
import java.util.function.Consumer
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

private const val LINE_DELAY_MILLIS = 400L
private const val LONG_LINE_DELAY_MILLIS = 150L
private const val LONG_LOG_LINES = 40
private const val HUGE_LOG_LINES = 2400
private const val HUGE_CHUNK = 200
private const val HUGE_HOLD_MILLIS = 5_000L
const val LONG_LOG_TOOL = "pyright"
const val HUGE_LOG_TOOL = "gopls"
const val MANUAL_TOOL = "clangd"
const val STILL_MISSING_TOOL = "rust-analyzer"
const val MANAGED_TOOL = "jdtls"

private val previewDownload = DownloadPlan(
    "download.eclipse.org", "https://download.eclipse.org/jdtls/snapshots/latest.txt",
    "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-{version}.tar.gz",
    Path.of("C:\\Users\\you\\.cc-manager\\tools\\jdtls"), LauncherSpec(LauncherSpec.Kind.JDTLS, ""),
)

/** Real catalog with a probe and installer that fake a mix of states and a slow successful install. */
fun previewToolchain(): ToolchainBackend {
    val catalog = ToolCatalog()
    val states = ToolState.entries
    fun statusOf(index: Int, id: String) = ToolStatus(id, states[index % states.size], if (index % states.size >= 1) "1.${index}.0" else "", if (states[index % states.size] == ToolState.FAILED) "The server exited right after start." else "")
    val all = catalog.all()
    val probe = mock(ToolProbe::class.java)
    `when`(probe.probeAll()).thenAnswer { all.mapIndexed { i, tool -> statusOf(i, tool.id()) } }
    `when`(probe.probeAllFresh()).thenAnswer { all.mapIndexed { i, tool -> statusOf(i, tool.id()) } }
    `when`(probe.probeFresh(anyString())).thenAnswer { call -> all.indexOfFirst { it.id() == call.getArgument<String>(0) }.let { statusOf(it, call.getArgument(0)) } }

    val installer = mock(ToolInstallService::class.java)
    `when`(installer.plan(anyString())).thenAnswer { call ->
        val tool = catalog.find(call.getArgument(0)).orElseThrow()
        val unblocked = setOf(LONG_LOG_TOOL, STILL_MISSING_TOOL, HUGE_LOG_TOOL, MANAGED_TOOL)
        val blocker = if (tool.prerequisite().isNotEmpty() && tool.id() !in unblocked) Optional.of("Install ${tool.prerequisite()} first") else Optional.empty()
        if (tool.id() == MANUAL_TOOL) Optional.of(InstallPlan(tool, listOf(), "", Optional.empty(), "winget install --id LLVM.LLVM -e", Optional.empty()))
        else if (tool.id() == MANAGED_TOOL) Optional.of(InstallPlan(tool, listOf(), "", blocker, "", Optional.of(previewDownload)))
        else Optional.of(InstallPlan(tool, listOf("npm", "install", "-g", tool.binary()), "npm install -g ${tool.binary()}", blocker, "", Optional.empty()))
    }
    `when`(installer.isManaged(anyString())).thenAnswer { call -> call.getArgument<String>(0) == MANAGED_TOOL }
    `when`(installer.uninstall(anyString())).thenAnswer { call ->
        Thread.sleep(LINE_DELAY_MILLIS)
        InstallResult(Outcome.INSTALLED, 0, ToolStatus(call.getArgument(0), ToolState.MISSING, "", ""), "Removed Java")
    }
    `when`(installer.install(anyString(), any())).thenAnswer { call ->
        val onOutput = call.getArgument<Consumer<String>>(1)
        val id = call.getArgument<String>(0)
        val lines = when (id) {
            LONG_LOG_TOOL -> (1..LONG_LOG_LINES).map { "npm http fetch GET 200 https://registry.npmjs.org/package-$it 12ms (cache hit)" }
            HUGE_LOG_TOOL -> (1..HUGE_LOG_LINES).map { "go: downloading golang.org/x/tools/module-$it v0.$it.0" } + "go: finished downloading modules"
            MANAGED_TOOL -> listOf("Latest version: 1.64.0-202611121920", "Downloading 100% (51.0 of 51.0 MB)", "Checksum verified", "Extracting 412 files", "Launcher written")
            else -> listOf("added 1 package in 2s", "1 package is looking for funding", "done")
        }
        val text = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (id != HUGE_LOG_TOOL) Thread.sleep(if (id == LONG_LOG_TOOL) LONG_LINE_DELAY_MILLIS else LINE_DELAY_MILLIS)
            text.append(line).append('\n')
            if (id != HUGE_LOG_TOOL || index % HUGE_CHUNK == 0 || index == lines.lastIndex) onOutput.accept(text.toString())
        }
        if (id == HUGE_LOG_TOOL) Thread.sleep(HUGE_HOLD_MILLIS)
        if (id == STILL_MISSING_TOOL) InstallResult(Outcome.STILL_MISSING, 0, ToolStatus(id, ToolState.MISSING, "", ""), "Installed, but $id is not on PATH yet.")
        else if (id == MANAGED_TOOL) InstallResult(Outcome.INSTALLED, 0, ToolStatus(id, ToolState.INSTALLED, "1.64.0", ""), "Installed Java 1.64.0")
        else InstallResult(Outcome.INSTALLED, 0, ToolStatus(id, ToolState.INSTALLED, "1.0.0", ""), "Installed $id")
    }
    return ToolchainBackend(catalog, probe, installer, previewProjectTools(catalog, ::statusOf))
}

private val previewDetected = setOf("typescript", "angular", "pyright")
private val previewUserLevel = setOf("pyright")

/** A TypeScript, Angular and Python project (the Python plugin is on for the user already): the TypeScript plugin is on, the others are off; toggling flips the fake state. */
private fun previewProjectTools(catalog: ToolCatalog, statusOf: (Int, String) -> ToolStatus): ProjectToolsService {
    val enabled = mutableSetOf("typescript")
    val tools = catalog.all().filter { it.kind() != ToolKind.PREREQUISITE }
    val service = mock(ProjectToolsService::class.java)
    val rows = {
        tools.mapIndexed { i, tool -> ProjectToolRow(tool, statusOf(i, tool.id()), tool.id() in previewDetected, tool.id() in enabled, tool.id() in previewUserLevel) }
    }
    `when`(service.rows(any())).thenAnswer { rows() }
    `when`(service.rowsFresh(any())).thenAnswer { rows() }
    doAnswer { enabled += it.getArgument<String>(1) }.`when`(service).enableLsp(any(), anyString())
    doAnswer { enabled -= it.getArgument<String>(1) }.`when`(service).disableLsp(any(), anyString())
    return service
}
