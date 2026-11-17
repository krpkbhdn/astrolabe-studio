package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContainerRuntimeDetectorTest {

    private static final String INSTANCE = "0123abcd";

    private static CommandRunner.Result exit(int code) {
        return new CommandRunner.Result(code, "", "", false);
    }

    @Test
    void reportsAvailableWhenVersionSucceeds() {
        var detector = new ContainerRuntimeDetector((command, timeout) -> exit(0));

        assertThat(detector.availability(IsolationBackend.DOCKER).available()).isTrue();
    }

    @Test
    void reportsNotInstalledWhenStartFails() {
        var detector = new ContainerRuntimeDetector((command, timeout) -> {
            throw new IOException("missing");
        });

        assertThat(detector.availability(IsolationBackend.PODMAN).reason()).contains("podman is not installed");
    }

    @Test
    void reportsDaemonNotRunningWhenVersionFails() {
        var detector = new ContainerRuntimeDetector((command, timeout) -> exit(1));

        assertThat(detector.availability(IsolationBackend.DOCKER).reason()).contains("daemon is not running");
        assertThat(detector.availability(IsolationBackend.PODMAN).reason()).contains("Podman machine");
    }

    @Test
    void neverPassesAnInvalidImageToTheRuntime() {
        var detector = new ContainerRuntimeDetector((command, timeout) -> {
            throw new AssertionError("must not run " + command);
        });

        for (var image : java.util.List.of("--privileged", "", "  ", "a b", "a\tb")) {
            assertThat(detector.imagePresent(IsolationBackend.DOCKER, image)).isFalse();
            assertThat(detector.pullImage(IsolationBackend.DOCKER, image)).isEqualTo("The container image name is invalid");
        }
    }

    @Test
    void checksImagePresenceAndPullsOnlyOnRequest() {
        var seen = new java.util.ArrayList<java.util.List<String>>();
        var detector = new ContainerRuntimeDetector((command, timeout) -> {
            seen.add(command);
            return exit(command.contains("pull") ? 1 : 0);
        });

        assertThat(detector.imagePresent(IsolationBackend.DOCKER, "img")).isTrue();
        assertThat(seen).containsExactly(java.util.List.of("docker", "image", "inspect", "img"));
        assertThat(detector.pullImage(IsolationBackend.DOCKER, "img")).contains("Could not pull image img");
    }

    @Test
    void killsEveryLabelledContainerFound() throws IOException {
        var seen = new java.util.ArrayList<java.util.List<String>>();
        var detector = new ContainerRuntimeDetector((command, timeout) -> {
            seen.add(command);
            return command.contains("ps") ? new CommandRunner.Result(0, "abc123def\nnot an id\n0123456789ab\n", "", false) : exit(0);
        });

        detector.killStaleContainers(IsolationBackend.DOCKER, INSTANCE);

        assertThat(seen.get(0)).containsExactly("docker", "ps", "-q", "--filter", "label=astrolabe-code=" + INSTANCE);
        assertThat(seen.get(1)).containsExactly("docker", "kill", "abc123def", "0123456789ab");
    }

    @Test
    void sweepIgnoresAnInvalidInstanceIdInsteadOfListingEveryContainer() {
        var seen = new java.util.ArrayList<java.util.List<String>>();
        var detector = new ContainerRuntimeDetector((command, timeout) -> {
            seen.add(command);
            return exit(0);
        });

        for (var invalid : java.util.Arrays.asList("", "abc", "ZZZZZZZZ", "1234567g", "1", null)) {
            detector.killStaleContainers(IsolationBackend.DOCKER, invalid);
        }

        assertThat(seen).isEmpty();
    }

    @Test
    void sweepFiltersOnTheOwnInstanceLabelOnly() throws IOException {
        var seen = new java.util.ArrayList<java.util.List<String>>();
        new ContainerRuntimeDetector((command, timeout) -> {
            seen.add(command);
            return exit(0);
        }).killStaleContainers(IsolationBackend.DOCKER, "feedface");

        assertThat(seen.getFirst()).contains("label=astrolabe-code=feedface").doesNotContain("label=astrolabe-code=" + INSTANCE);
    }

    @Test
    void sweepDoesNothingWhenNoneRunOrNoRuntime() {
        var seen = new java.util.ArrayList<java.util.List<String>>();
        new ContainerRuntimeDetector((command, timeout) -> {
            seen.add(command);
            return exit(0);
        }).killStaleContainers(IsolationBackend.PODMAN, INSTANCE);
        new ContainerRuntimeDetector((command, timeout) -> {
            throw new IOException("missing");
        }).killStaleContainers(IsolationBackend.DOCKER, INSTANCE);

        assertThat(seen).hasSize(1);
    }
}
