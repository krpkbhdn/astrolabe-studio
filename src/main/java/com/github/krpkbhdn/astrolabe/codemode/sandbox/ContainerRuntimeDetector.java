package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Tells whether Docker or Podman can run the sandbox, with a human reason when not; pulling the image is always explicit. */
@Component
public class ContainerRuntimeDetector {

    public record Availability(boolean available, String reason) {

        static Availability ready() {
            return new Availability(true, "");
        }

        static Availability unavailable(String reason) {
            return new Availability(false, reason);
        }
    }

    /** Runs one short command; {@link IOException} means it could not be started. */
    @FunctionalInterface
    public interface Commands {
        CommandRunner.Result run(List<String> command, Duration timeout) throws IOException;
    }

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

    private final Commands commands;

    public ContainerRuntimeDetector() {
        this((command, timeout) -> CommandRunner.run(command, null, timeout));
    }

    ContainerRuntimeDetector(Commands commands) {
        this.commands = commands;
    }

    public Availability availability(IsolationBackend runtime) {
        var name = executable(runtime);
        try {
            return commands.run(List.of(name, "version"), PROBE_TIMEOUT).succeeded()
                    ? Availability.ready()
                    : Availability.unavailable(runtime == IsolationBackend.PODMAN
                            ? "Podman is installed but not responding; start the Podman machine"
                            : "Docker is installed but its daemon is not running");
        } catch (IOException e) {
            return Availability.unavailable(name + " is not installed or not on PATH");
        }
    }

    static final String INVALID_IMAGE = "The container image name is invalid";

    /** An image that is blank, starts with '-' (would be read as an option) or holds whitespace or control characters never reaches a command line. */
    static boolean validImage(String image) {
        return image != null && !image.isBlank() && image.charAt(0) != '-' && image.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    public boolean imagePresent(IsolationBackend runtime, String image) {
        if (!validImage(image)) {
            return false;
        }
        try {
            return commands.run(List.of(executable(runtime), "image", "inspect", image), PROBE_TIMEOUT).succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    /** @return an empty string on success, otherwise why the pull failed */
    public String pullImage(IsolationBackend runtime, String image) {
        if (!validImage(image)) {
            return INVALID_IMAGE;
        }
        try {
            var result = commands.run(List.of(executable(runtime), "pull", image), PULL_TIMEOUT);
            return result.succeeded() ? "" : "Could not pull image " + image + (result.timedOut() ? " (timed out)" : "");
        } catch (IOException e) {
            return executable(runtime) + " is not installed or not on PATH";
        }
    }

    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{6,64}");
    private static final Pattern INSTANCE_ID = Pattern.compile("[0-9a-f]{8,64}");

    static boolean validInstanceId(String instanceId) {
        return instanceId != null && INSTANCE_ID.matcher(instanceId).matches();
    }

    /** The label of every sandbox container of one app instance (one data dir), so a second instance never sweeps another's containers. */
    static String label(String instanceId) {
        return "astrolabe-code=" + instanceId;
    }

    /** Kills this instance's sandbox containers a crashed app left running; call once at application start. Best effort, never throws. */
    public void killStaleContainers(IsolationBackend runtime, String instanceId) {
        if (!validInstanceId(instanceId)) {
            return;
        }
        try {
            var listed = commands.run(List.of(executable(runtime), "ps", "-q", "--filter", "label=" + label(instanceId)), PROBE_TIMEOUT);
            var ids = listed.succeeded() ? listed.stdout().lines().map(String::strip).filter(id -> CONTAINER_ID.matcher(id).matches()).toList() : List.<String>of();
            if (!ids.isEmpty()) {
                var command = new ArrayList<>(List.of(executable(runtime), "kill"));
                command.addAll(ids);
                commands.run(command, KILL_TIMEOUT);
            }
        } catch (IOException | IllegalArgumentException e) {
            // no runtime, nothing to sweep
        }
    }

    static String executable(IsolationBackend runtime) {
        return switch (runtime) {
            case DOCKER -> "docker";
            case PODMAN -> "podman";
            case GRAALJS -> throw new IllegalArgumentException("GraalJS is not a container runtime");
        };
    }
}
