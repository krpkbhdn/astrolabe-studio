package com.github.krpkbhdn.astrolabe.claude.launch;

import com.pty4j.PtyProcess;
import java.nio.file.Path;

/** [directory] is the folder claude was really started in; null when the caller does not say. */
public record LaunchedSession(String astrolabeSessionId, long projectId, String label, PtyProcess process, Path directory) {

    public LaunchedSession(String astrolabeSessionId, long projectId, String label, PtyProcess process) {
        this(astrolabeSessionId, projectId, label, process, null);
    }
}
