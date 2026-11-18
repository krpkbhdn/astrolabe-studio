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
 * the bundle records one line per project in a marker file of the jdtls workspace, after jdtls imported the project.
 */
final class GradleOutputRedirectCheck {

    /** {@code failure} is set only by an explicit FAILED line; {@code pending} means jdtls has not recorded this project yet. */
    record Outcome(Optional<String> failure, boolean pending) {
        static final Outcome FINE = new Outcome(Optional.empty(), false);
    }

    private enum Marker { REDIRECTED, FAILED, MISSING }

    private static final List<String> GRADLE_FILES =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static final String OK = " OK";
    private static final String FAILED = " FAILED";

    private GradleOutputRedirectCheck() {
    }

    /** @return fine when not a Gradle project, jdtls never opened it, or it redirected; pending while the marker has no entry for it. */
    static Outcome check(Path jdtlsStateDir, Path projectDir, ToolChannel channel) {
        if (GRADLE_FILES.stream().noneMatch(name -> Files.isRegularFile(projectDir.resolve(name)))) {
            return Outcome.FINE;
        }
        if (!Files.isDirectory(JdtlsWorkspace.folderOf(jdtlsStateDir, projectDir).resolve(".metadata"))) {
            return Outcome.FINE;
        }
        return switch (markerOf(JdtlsWorkspace.markerFile(jdtlsStateDir, projectDir), projectDir)) {
            case REDIRECTED -> Outcome.FINE;
            case MISSING -> new Outcome(Optional.empty(), true);
            case FAILED -> new Outcome(Optional.of("Gradle output redirect did not run (" + channel + ")"), false);
        };
    }

    private static Marker markerOf(Path marker, Path projectDir) {
        List<String> lines;
        try {
            lines = Files.readAllLines(marker);
        } catch (IOException | RuntimeException e) {
            return Marker.MISSING;
        }
        var project = projectDir.toAbsolutePath().normalize();
        if (lines.stream().anyMatch(line -> statusFor(line, project, FAILED))) {
            return Marker.FAILED;
        }
        return lines.stream().anyMatch(line -> statusFor(line, project, OK)) ? Marker.REDIRECTED : Marker.MISSING;
    }

    private static boolean statusFor(String line, Path project, String status) {
        int at = line.indexOf(status + " ");
        if (at < 0) {
            at = line.endsWith(status) ? line.length() - status.length() : -1;
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
