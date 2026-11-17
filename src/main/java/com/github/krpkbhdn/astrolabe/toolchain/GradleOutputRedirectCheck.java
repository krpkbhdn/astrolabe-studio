package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.toolchain.managed.JdtlsWorkspace;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Tells whether jdtls, once it opened a Gradle project, ran the bundle that redirects the project's output to build/eclipse-out;
 * the bundle records one line per project in a marker file of the jdtls workspace.
 */
final class GradleOutputRedirectCheck {

    private static final List<String> GRADLE_FILES =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static final String OK = " OK";
    private static final String FAILED = " FAILED";

    private GradleOutputRedirectCheck() {
    }

    /** @return the failure detail, empty when fine, not a Gradle project, or jdtls never opened it. */
    static Optional<String> failure(Path jdtlsStateDir, Path projectDir, ToolChannel channel) {
        if (GRADLE_FILES.stream().noneMatch(name -> Files.isRegularFile(projectDir.resolve(name)))) {
            return Optional.empty();
        }
        var marker = JdtlsWorkspace.markerFile(jdtlsStateDir, projectDir);
        if (!Files.isDirectory(JdtlsWorkspace.folderOf(jdtlsStateDir, projectDir).resolve(".metadata"))) {
            return Optional.empty();
        }
        return redirected(marker, projectDir) ? Optional.empty()
                : Optional.of("Gradle output redirect did not run (" + channel + ")");
    }

    private static boolean redirected(Path marker, Path projectDir) {
        List<String> lines;
        try {
            lines = Files.readAllLines(marker);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (lines.stream().anyMatch(line -> line.contains(FAILED))) {
            return false;
        }
        var project = projectDir.toAbsolutePath().normalize();
        return lines.stream().anyMatch(line -> okFor(line, project));
    }

    private static boolean okFor(String line, Path project) {
        int at = line.indexOf(OK + " ");
        if (at < 0) {
            at = line.endsWith(OK) ? line.length() - OK.length() : -1;
        }
        if (at < 0) {
            return false;
        }
        try {
            return Path.of(line.substring(0, at).strip()).toAbsolutePath().normalize().equals(project);
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
