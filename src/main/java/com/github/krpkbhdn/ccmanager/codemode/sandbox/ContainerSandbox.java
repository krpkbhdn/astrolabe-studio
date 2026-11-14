package com.github.krpkbhdn.ccmanager.codemode.sandbox;

import com.github.krpkbhdn.ccmanager.codemode.BoundedLines;
import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.HostBridge;
import com.github.krpkbhdn.ccmanager.codemode.ScriptResult;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs scripts in a throwaway Docker/Podman container (no network, read-only, no mounts, no capabilities), the strongest isolation backend.
 * The runner travels base64 in an environment variable so no argument needs quoting on Windows; script and tool calls are JSON lines over stdin/stdout.
 */
public class ContainerSandbox implements ScriptSandbox {

    /** Starts a process; replaced by a fake in tests. */
    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private static final String BOOTSTRAP = "eval(atob(process.env.CCM_RUNNER))";
    private static final String RUNNER_RESOURCE = "/code-mode/runner.js";
    private static final int PIDS_LIMIT = 128;
    /** Extra seconds past maxScriptTime before the in-container {@code timeout} kills node, so a crashed app never leaves a spinning container. */
    private static final long WATCHDOG_GRACE_SECONDS = 10;
    private static final Pattern MEMORY = Pattern.compile("[1-9]\\d{0,9}[bkmgBKMG]?");
    private static final double MAX_CPUS = 64;
    static final String INVALID_LIMITS = "The container memory or cpu limit is invalid";
    private static final String NOBODY_USER = "65534";
    private static final String TMPFS = "/tmp:size=16m,noexec,nosuid";
    /** A tool result echoed by the script is the largest legitimate line; anything longer is a flood, not a message. */
    private static final int MAX_LINE_CHARS = 4 * 1024 * 1024;
    private static final String LINE_TOO_LONG = "The sandbox sent a line over the size limit";

    private final IsolationBackend runtime;
    private final CodeModeProperties properties;
    private final JsonMapper mapper;
    private final ProcessStarter starter;
    private final String runnerBase64;
    private final String instanceId;

    public ContainerSandbox(IsolationBackend runtime, CodeModeProperties properties, JsonMapper mapper, String instanceId) {
        this(runtime, properties, mapper, instanceId, command -> new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }

    ContainerSandbox(IsolationBackend runtime, CodeModeProperties properties, JsonMapper mapper, String instanceId, ProcessStarter starter) {
        if (!ContainerRuntimeDetector.validInstanceId(instanceId)) {
            throw new IllegalArgumentException("The instance id must be 8 to 64 lowercase hex characters");
        }
        this.instanceId = instanceId;
        this.runtime = runtime;
        this.properties = properties;
        this.mapper = mapper;
        this.starter = starter;
        this.runnerBase64 = Base64.getEncoder().encodeToString(readRunner());
    }

    @Override
    public ScriptResult run(String code, HostBridge bridge, SessionContext session) {
        if (!ContainerRuntimeDetector.validImage(properties.image())) {
            return ScriptResult.failed(ContainerRuntimeDetector.INVALID_IMAGE);
        }
        if (!validLimits(properties.containerMemory(), properties.containerCpus())) {
            return ScriptResult.failed(INVALID_LIMITS);
        }
        var name = "ccm-code-" + UUID.randomUUID();
        Process process;
        try {
            process = starter.start(command(name));
        } catch (IOException e) {
            return ScriptResult.failed("The container could not be started: " + ContainerRuntimeDetector.executable(runtime) + " is unavailable");
        }
        var output = new CappedOutput(properties.maxOutputChars());
        var outcome = new FutureTask<>(() -> converse(process, code, bridge, output));
        Thread.startVirtualThread(outcome);
        try {
            return outcome.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ScriptResult.failed("The script hit the hard cap of " + properties.timeout().toSeconds() + " s (cc-manager.code-mode.max-script-time)", output.render());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScriptResult.failed("The script was cancelled", output.render());
        } catch (ExecutionException e) {
            return ScriptResult.failed("The sandbox failed: " + e.getCause().getClass().getSimpleName(), output.render());
        } finally {
            outcome.cancel(true);
            stop(process, name);
        }
    }

    /** Memory is digits with an optional b/k/m/g unit and never zero (unlimited), cpus a finite number in (0, 64]; neither can start with '-'. */
    static boolean validLimits(String memory, double cpus) {
        return memory != null && MEMORY.matcher(memory).matches() && cpus > 0 && cpus <= MAX_CPUS;
    }

    private List<String> command(String name) {
        return List.of(ContainerRuntimeDetector.executable(runtime), "run", "--rm", "-i", "--pull", "never", "--network", "none",
                "--read-only", "--tmpfs", TMPFS, "--memory", properties.containerMemory(), "--memory-swap", properties.containerMemory(), "--cpus", BigDecimal.valueOf(properties.containerCpus()).toPlainString(),
                "--pids-limit", String.valueOf(PIDS_LIMIT), "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", NOBODY_USER, "--label", ContainerRuntimeDetector.label(instanceId), "--name", name, "-e", "CCM_RUNNER=" + runnerBase64, properties.image(),
                "timeout", "-s", "KILL", String.valueOf(properties.maxScriptTime().toSeconds() + WATCHDOG_GRACE_SECONDS), "node", "-e", BOOTSTRAP);
    }

    private ScriptResult converse(Process process, String code, HostBridge bridge, CappedOutput output) {
        try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                var out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            var run = mapper.createObjectNode().put("type", "run").put("code", code);
            var index = bridge.serverIndexJson();
            if (index != null) {
                run.put("servers", index);
            }
            send(out, run);
            String line;
            while ((line = BoundedLines.read(in, MAX_LINE_CHARS)) != null) {
                var message = parse(line);
                if (message == null) {
                    return ScriptResult.failed("The sandbox sent a malformed message", output.render());
                }
                switch (message.path("type").asString("")) {
                    case "log" -> logLine(output, bridge, message.path("line").asString(""));
                    case "call" -> send(out, answer(message, bridge));
                    case "done" -> {
                        return finished(output, message.path("value"));
                    }
                    case "error" -> {
                        return ScriptResult.failed(message.path("message").asString("unknown"),output.render());
                    }
                    default -> {
                        return ScriptResult.failed("The sandbox sent an unknown message", output.render());
                    }
                }
            }
            return ScriptResult.failed("The container exited without a result", output.render());
        } catch (IOException e) {
            return ScriptResult.failed("The container connection was lost", output.render());
        } catch (BoundedLines.TooLongException e) {
            return ScriptResult.failed(LINE_TOO_LONG, output.render());
        }
    }

    private JsonNode parse(String line) {
        try {
            var node = mapper.readTree(line);
            return node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void logLine(CappedOutput output, HostBridge bridge, String line) {
        output.appendLine(line);
        bridge.log(line);
    }

    private JsonNode answer(JsonNode call, HostBridge bridge) {
        var reply = mapper.createObjectNode().put("type", "result").put("id", call.path("id").asInt());
        try {
            var value = bridge.callTool(call.path("server").asString(""), call.path("tool").asString(""), call.path("args").toString());
            return reply.put("value", value);
        } catch (RuntimeException e) {
            return reply.put("error", e.getMessage() == null ? "The tool call failed" : e.getMessage());
        }
    }

    private ScriptResult finished(CappedOutput output, JsonNode value) {
        if (!value.isNull() && !value.isMissingNode() && !(value.isString() && value.asString().isEmpty())) {
            output.appendLine(value.isString() ? value.asString() : value.toString());
        }
        return ScriptResult.ok(output.render(), output.truncated());
    }

    private void send(Writer out, JsonNode message) {
        try {
            out.write(message + "\n");
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void stop(Process process, String name) {
        if (process.isAlive()) {
            try {
                starter.start(List.of(ContainerRuntimeDetector.executable(runtime), "kill", name)).waitFor(5, TimeUnit.SECONDS);
            } catch (IOException e) {
                // the process destroy below is the fallback
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    private static byte[] readRunner() {
        try (var stream = ContainerSandbox.class.getResourceAsStream(RUNNER_RESOURCE)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
