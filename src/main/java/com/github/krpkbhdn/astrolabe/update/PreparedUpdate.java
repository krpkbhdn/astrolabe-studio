package com.github.krpkbhdn.astrolabe.update;

import java.nio.file.Path;

/** What {@link UpdateService#prepareInstall} made from a verified download; handed back to launch it or to discard it. */
public record PreparedUpdate(Path path, Path download) {
}
