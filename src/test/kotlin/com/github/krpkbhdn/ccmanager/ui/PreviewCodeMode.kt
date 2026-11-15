package com.github.krpkbhdn.ccmanager.ui

import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationSettingsService
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ContainerRuntimeDetector
import com.github.krpkbhdn.ccmanager.ui.settings.CodeModeBackend
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Code Mode on with one real and one vanished server, so the status bar chip shows its yellow state. */
internal fun previewProjectCodeMode(): ProjectCodeModeService = mock(ProjectCodeModeService::class.java).also {
    `when`(it.get(anyLong())).thenReturn(ProjectCodeMode(1, true, listOf("github", "ghost")))
}

internal fun previewCodeModeBackend(): CodeModeBackend {
    val isolation = mock(IsolationSettingsService::class.java)
    `when`(isolation.get()).thenReturn(IsolationBackend.GRAALJS)
    return CodeModeBackend(isolation, mock(ContainerRuntimeDetector::class.java), "node:22-alpine")
}
