package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.toolchain.managed.JdtlsWorkspace;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ToolChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleOutputRedirectCheckTest {

    @TempDir
    Path temp;

    private Path state;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        state = temp.resolve("state");
        project = temp.resolve("my project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("build.gradle.kts"), "");
        Files.createDirectories(JdtlsWorkspace.folderOf(state, project).resolve(".metadata"));
    }

    private void marker(String... lines) throws IOException {
        var file = JdtlsWorkspace.markerFile(state, project);
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines));
    }

    private GradleOutputRedirectCheck.Outcome check() {
        return GradleOutputRedirectCheck.check(state, project, ToolChannel.VERIFIED);
    }

    @Test
    void absentMarkerIsPendingNotFailed() {
        var outcome = check();
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.pending()).isTrue();
    }

    @Test
    void emptyMarkerIsPendingNotFailed() throws IOException {
        marker();
        Files.writeString(JdtlsWorkspace.markerFile(state, project), "");
        var outcome = check();
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.pending()).isTrue();
    }

    @Test
    void okLineForAPathWithSpacesIsFine() throws IOException {
        marker(project + " OK build/eclipse-out");
        assertThat(check()).isEqualTo(GradleOutputRedirectCheck.Outcome.FINE);
    }

    @Test
    void okLineWithoutSuffixIsFine() throws IOException {
        marker(project + " OK");
        assertThat(check()).isEqualTo(GradleOutputRedirectCheck.Outcome.FINE);
    }

    @Test
    void okLineOfAnotherProjectIsPending() throws IOException {
        marker(temp.resolve("other") + " OK build/eclipse-out");
        assertThat(check().pending()).isTrue();
        assertThat(check().failure()).isEmpty();
    }

    @Test
    void failedLineOfThisProjectIsFailed() throws IOException {
        marker(project + " FAILED java.io.IOException");
        assertThat(check().failure()).isPresent();
        assertThat(check().pending()).isFalse();
    }

    @Test
    void failedLineOfAnotherProjectIsNotFailedForThisOne() throws IOException {
        marker(temp.resolve("other") + " FAILED java.io.IOException");
        assertThat(check().failure()).isEmpty();
    }

    @Test
    void mixedSlashesInTheMarkerMatchTheProject() throws IOException {
        marker(project.toString().replace('\\', '/') + " OK build/eclipse-out");
        assertThat(check()).isEqualTo(GradleOutputRedirectCheck.Outcome.FINE);
    }
}
