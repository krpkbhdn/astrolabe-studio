package com.github.krpkbhdn.ccmanager.codemode;

import com.github.krpkbhdn.ccmanager.codemode.catalog.ToolSearch;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationSettingsService;
import com.github.krpkbhdn.ccmanager.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.ccmanager.codemode.policy.ExecutionRegistry;
import com.github.krpkbhdn.ccmanager.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ContainerRuntimeDetector;
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ScriptSandbox;
import com.github.krpkbhdn.ccmanager.review.PromptText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Code Mode for the sessions of projects that opted in: lists and searches the project's MCP servers and runs one script at a time per session
 * in the chosen isolation backend, failing (never falling back) when it is unavailable. The script timeout does not count the user's answer time.
 */
@Service
public class CodeModeService {

    static final String NOT_ENABLED = "Code Mode is not enabled for this project";
    private static final long STOP_GRACE_MILLIS = 2_000;

    private final ProjectCodeModeService modes;
    private final ServerConnections connections;
    private final ToolCallGate gate;
    private final CodeModeAudit audit;
    private final ExecutionRegistry executions;
    private final CodeModeSandboxes sandboxes;
    private final IsolationSettingsService isolation;
    private final ContainerRuntimeDetector detector;
    private final CodeModeProperties properties;
    private final CodeModeSessionCleanup cleanup;
    private final Clock clock;
    private volatile Environment checkedEnvironment = new Environment(null, Instant.MIN);

    private record Environment(IsolationBackend backend, Instant checkedAt) {}

    @Autowired
    public CodeModeService(ProjectCodeModeService modes, ServerConnections connections, ToolCallGate gate, CodeModeAudit audit,
            ExecutionRegistry executions, CodeModeSandboxes sandboxes, IsolationSettingsService isolation, ContainerRuntimeDetector detector,
            CodeModeProperties properties, CodeModeSessionCleanup cleanup) {
        this(modes, connections, gate, audit, executions, sandboxes, isolation, detector, properties, cleanup, Clock.systemUTC());
    }

    CodeModeService(ProjectCodeModeService modes, ServerConnections connections, ToolCallGate gate, CodeModeAudit audit,
            ExecutionRegistry executions, CodeModeSandboxes sandboxes, IsolationSettingsService isolation, ContainerRuntimeDetector detector,
            CodeModeProperties properties, CodeModeSessionCleanup cleanup, Clock clock) {
        this.clock = clock;
        this.modes = modes;
        this.connections = connections;
        this.gate = gate;
        this.audit = audit;
        this.executions = executions;
        this.sandboxes = sandboxes;
        this.isolation = isolation;
        this.detector = detector;
        this.properties = properties;
        this.cleanup = cleanup;
    }

    public List<ServerStatus> listServers(SessionContext session) {
        requireEnabled(session);
        return connections.statuses(session);
    }

    /** Signatures of the best matches, or all tools with a summary line for a blank query; servers that cannot be used are listed after them. */
    public String searchTools(SessionContext session, String query, String serverFilter) {
        requireEnabled(session);
        var only = serverFilter.isBlank() ? null : connections.resolve(session, serverFilter);
        var catalog = only == null ? connections.connectAll(session) : connections.connectOnly(session, only);
        var found = ToolSearch.search(only == null ? catalog.all() : catalog.byServer(only), query, properties.searchLimit());
        var unavailable = connections.statuses(session).stream().filter(server -> server.problem().isPresent())
                .map(server -> "- " + PromptText.oneLine(server.name() + ": " + server.problem().get())).toList();
        var notes = unavailable.isEmpty() ? "" : "Unavailable servers:\n" + String.join("\n", unavailable);
        if (found.isBlank()) {
            return "No matching tools." + (notes.isEmpty() ? "" : "\n\n" + notes);
        }
        return notes.isEmpty() ? found : found + "\n\n" + notes;
    }

    /** @throws IllegalStateException when Code Mode is off, the chosen sandbox is unavailable or the session already runs a script */
    public ScriptResult execute(SessionContext session, String code) {
        return execute(session, code, new RunCancellation());
    }

    /** @param request cancels only the run this call starts; checked again once the run is registered */
    public ScriptResult execute(SessionContext session, String code, RunCancellation request) {
        requireEnabled(session);
        var sandbox = sandbox();
        var cancelled = new AtomicBoolean();
        var approvals = new ApprovalWait();
        var runner = Thread.currentThread();
        var finished = new AtomicBoolean();
        var bridge = new ScriptBridge(session, connections, gate, audit, cleanup.images(session.sessionId()), cancelled, approvals,
                callLimit());
        var started = System.nanoTime();
        Runnable stop = () -> {
            cancelled.set(true);
            synchronized (finished) {
                if (!finished.get()) {
                    runner.interrupt();
                }
            }
        };
        try (var running = executions.register(session.sessionId(), () -> deadlineNanos(started, approvals), stop, approvals::openTool)) {
            request.bind(stop);
            if (request.isCancelled()) {
                return ScriptResult.failed(ScriptBridge.CANCELLED);
            }
            return runWithin(sandbox, code, bridge, session, approvals, cancelled, started);
        } finally {
            cancelled.set(true);
            synchronized (finished) {
                finished.set(true);
            }
            Thread.interrupted();
        }
    }

    private Duration callLimit() {
        return properties.toolCallTimeout().compareTo(properties.timeout()) < 0 ? properties.toolCallTimeout() : properties.timeout();
    }

    /** {@code timeout} plus the time spent waiting for the user's answers, never past {@code maxScriptTime}. */
    private long deadlineNanos(long started, ApprovalWait approvals) {
        return started + Math.min(properties.timeout().toNanos() + approvals.nanos(), properties.maxScriptTime().toNanos());
    }

    /** The script gets its deadline; once it ends, no tool call can start anymore. */
    private ScriptResult runWithin(ScriptSandbox sandbox, String code, ScriptBridge bridge, SessionContext session, ApprovalWait approvals,
            AtomicBoolean cancelled, long started) {
        var outcome = new AtomicReference<ScriptResult>();
        var worker = Thread.ofPlatform().name("code-mode-run").daemon().start(() -> outcome.set(runQuietly(sandbox, code, bridge, session)));
        try {
            long remaining;
            while (worker.isAlive() && (remaining = deadlineNanos(started, approvals) - System.nanoTime()) > 0) {
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            if (!worker.isAlive()) {
                return outcome.get();
            }
            return stopped(worker, outcome, cancelled, timeoutMessage(approvals));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return stopped(worker, outcome, cancelled, ScriptBridge.CANCELLED);
        }
    }

    private String timeoutMessage(ApprovalWait approvals) {
        var capped = properties.timeout().toNanos() + approvals.nanos() >= properties.maxScriptTime().toNanos();
        return capped
                ? "The script hit the hard cap of " + properties.maxScriptTime().toSeconds() + " s (cc-manager.code-mode.max-script-time); raise that property in cc-manager's configuration"
                : "The script timed out after " + properties.timeout().toSeconds() + " s (cc-manager.code-mode.timeout, the time spent waiting for the user's answers is not counted); "
                        + "raise that property in cc-manager's configuration";
    }

    private static ScriptResult runQuietly(ScriptSandbox sandbox, String code, ScriptBridge bridge, SessionContext session) {
        try {
            return sandbox.run(code, bridge, session);
        } catch (RuntimeException e) {
            return ScriptResult.failed("The sandbox failed: " + e.getClass().getSimpleName());
        }
    }

    /** Interrupts the worker and keeps what the script printed, if the sandbox reports it within the grace period. */
    private static ScriptResult stopped(Thread worker, AtomicReference<ScriptResult> outcome, AtomicBoolean cancelled, String reason) {
        cancelled.set(true);
        worker.interrupt();
        var wasInterrupted = Thread.interrupted();
        try {
            worker.join(STOP_GRACE_MILLIS);
        } catch (InterruptedException e) {
            wasInterrupted = true;
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
        var partial = outcome.get();
        return ScriptResult.failed(reason, partial == null ? "" : partial.output());
    }

    private void requireEnabled(SessionContext session) {
        if (!modes.get(session.projectId()).enabled()) {
            throw new IllegalStateException(NOT_ENABLED);
        }
    }

    /** A container runtime and image that checked out are trusted for {@code environmentCheckTtl}; a backend change or a failed check drops that. */
    private ScriptSandbox sandbox() {
        var backend = isolation.get();
        if (backend != IsolationBackend.GRAALJS && !environmentTrusted(backend)) {
            checkedEnvironment = new Environment(null, Instant.MIN);
            var availability = detector.availability(backend);
            if (!availability.available()) {
                throw new IllegalStateException(availability.reason());
            }
            if (!detector.imagePresent(backend, properties.image())) {
                throw new IllegalStateException("The sandbox image " + properties.image() + " is not present for " + backend + "; pull it in the Code Mode settings");
            }
            checkedEnvironment = new Environment(backend, clock.instant());
        }
        return sandboxes.forBackend(backend);
    }

    private boolean environmentTrusted(IsolationBackend backend) {
        var checked = checkedEnvironment;
        return checked.backend() == backend && checked.checkedAt().plus(properties.environmentCheckTtl()).isAfter(clock.instant());
    }
}
