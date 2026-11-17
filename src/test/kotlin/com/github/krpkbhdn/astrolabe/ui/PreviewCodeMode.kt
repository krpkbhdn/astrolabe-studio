package com.github.krpkbhdn.astrolabe.ui

import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeMode
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationSettingsService
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector
import com.github.krpkbhdn.astrolabe.ui.settings.CodeModeBackend
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Code Mode on with one real and one vanished server, so the status bar chip shows its yellow state. */
internal fun previewProjectCodeMode(): ProjectCodeModeService = mock(ProjectCodeModeService::class.java).also {
    `when`(it.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("github", "ghost"), com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.ASK))
}

internal fun previewCodeModeBackend(): CodeModeBackend {
    val isolation = mock(IsolationSettingsService::class.java)
    `when`(isolation.get()).thenReturn(IsolationBackend.GRAALJS)
    return CodeModeBackend(isolation, mock(ContainerRuntimeDetector::class.java), "node:22-alpine")
}
