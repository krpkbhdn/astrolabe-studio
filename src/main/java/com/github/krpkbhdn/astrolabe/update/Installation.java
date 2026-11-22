package com.github.krpkbhdn.astrolabe.update;

import java.nio.file.Path;

/**
 * @param arch {@code x64} or {@code arm64}
 * @param root the installation directory (the .app bundle on macOS)
 */
public record Installation(InstallKind kind, String arch, Path root, Path appExecutable) {}
