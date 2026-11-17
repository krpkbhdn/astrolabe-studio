package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationSettingsService;
import com.github.krpkbhdn.astrolabe.codemode.policy.ExecutionRegistry;
import com.github.krpkbhdn.astrolabe.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What Code Mode keeps per session (image files, clients, grants) and when it goes: a running script is cancelled once its session is interrupted or ended,
 * everything else is released when the session is offline. Leftovers of an earlier run are swept at start.
 */
@Component
public class CodeModeSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(CodeModeSessionCleanup.class);
    private static final String DIRECTORY = "code-mode";

    private final SessionRegistry sessions;
    private final ProjectCodeModeService modes;
    private final ServerConnections connections;
    private final ToolCallGate gate;
    private final ExecutionRegistry executions;
    private final IsolationSettingsService isolation;
    private final ContainerRuntimeDetector detector;
    private final CodeModeSandboxes sandboxes;
    private final CodeModeProperties properties;
    private final Path directory;
    private final Map<String, SessionImages> images = new ConcurrentHashMap<>();
    private final Set<String> forgetting = ConcurrentHashMap.newKeySet();
    private final Set<String> confirming = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor cleanup = new ScheduledThreadPoolExecutor(1,
            task -> Thread.ofPlatform().name("code-mode-cleanup").daemon().unstarted(task));

    public CodeModeSessionCleanup(SessionRegistry sessions, ProjectCodeModeService modes, ServerConnections connections, ToolCallGate gate,
            ExecutionRegistry executions, IsolationSettingsService isolation, ContainerRuntimeDetector detector, CodeModeSandboxes sandboxes,
            CodeModeProperties properties, AppProperties app) {
        this.sessions = sessions;
        this.modes = modes;
        this.connections = connections;
        this.gate = gate;
        this.executions = executions;
        this.isolation = isolation;
        this.detector = detector;
        this.sandboxes = sandboxes;
        this.properties = properties;
        this.directory = app.dataDir().resolve(DIRECTORY);
        sessions.subscribe(this::onSessionsChanged);
    }

    SessionImages images(String sessionId) {
        return images.computeIfAbsent(sessionId, id -> new SessionImages(directory.resolve(id), properties.maxImagesPerSession(),
                properties.maxImageBytesPerSession()));
    }

    /**
     * Runs on the hook worker thread, whose queue can lag behind the real state: a session the registry does not know yet is never judged, and an IDLE reading
     * (Esc fires no Stop) is confirmed on the cleanup thread before the script is cancelled. The blocking release of ended sessions runs there too.
     */
    private void onSessionsChanged() {
        try {
            executions.runningSessionIds().forEach(this::cancelWhenInterrupted);
            var known = new HashSet<>(connections.sessionIds());
            known.addAll(images.keySet());
            known.addAll(modes.rememberedSessionIds());
            known.stream().filter(this::hasEnded).filter(forgetting::add).forEach(id -> cleanup.execute(() -> forgetQuietly(id)));
        } catch (RuntimeException e) {
            log.warn("Code Mode session cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private void cancelWhenInterrupted(String sessionId) {
        var status = statusOf(sessionId);
        if (status.isPresent() && status.get() == AgentStatus.OFFLINE) {
            executions.cancel(sessionId);
        } else if (status.isPresent() && status.get() == AgentStatus.IDLE && confirming.add(sessionId)) {
            cleanup.schedule(() -> confirmIdle(sessionId), properties.idleCancelDelay().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Re-reads the status once the registry had time to catch up; only a session still idle (or gone offline) loses its script. */
    void confirmIdle(String sessionId) {
        try {
            var status = statusOf(sessionId);
            if (status.isPresent() && (status.get() == AgentStatus.IDLE || status.get() == AgentStatus.OFFLINE)) {
                executions.cancel(sessionId);
            }
        } catch (RuntimeException e) {
            log.warn("Code Mode idle check failed: {}", e.getClass().getSimpleName());
        } finally {
            confirming.remove(sessionId);
        }
    }

    private Optional<AgentStatus> statusOf(String sessionId) {
        return sessions.find(sessionId).map(SessionSnapshot::status);
    }

    private boolean hasEnded(String sessionId) {
        var status = statusOf(sessionId);
        return status.isPresent() ? status.get() == AgentStatus.OFFLINE : !executions.isRunning(sessionId);
    }

    private void forgetQuietly(String sessionId) {
        try {
            executions.cancel(sessionId);
            connections.closeSession(sessionId);
            gate.forgetSession(sessionId);
            modes.forgetDirectory(sessionId);
            var stored = images.remove(sessionId);
            if (stored != null) {
                stored.close();
            }
            deleteQuietly(directory.resolve(sessionId));
        } catch (RuntimeException e) {
            log.warn("Code Mode session cleanup failed: {}", e.getClass().getSimpleName());
        } finally {
            forgetting.remove(sessionId);
        }
    }

    /** Image folders of sessions of an earlier run (crash or restart) are never read again. */
    @PostConstruct
    void sweepLeftoverImages() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.newDirectoryStream(directory)) {
            children.forEach(CodeModeSessionCleanup::deleteQuietly);
        } catch (IOException | RuntimeException e) {
            log.warn("Code Mode image cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Only when a container runtime is the saved backend, and on its own thread: the probe can take seconds and must not delay session cleanup or start. */
    @PostConstruct
    void sweepStaleContainers() {
        try {
            var backend = isolation.get();
            if (backend != IsolationBackend.DOCKER && backend != IsolationBackend.PODMAN) {
                return;
            }
            Thread.ofVirtual().name("code-mode-container-sweep").start(() -> killStaleContainers(backend));
        } catch (RuntimeException e) {
            log.warn("Code Mode container cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private void killStaleContainers(IsolationBackend runtime) {
        try {
            if (detector.availability(runtime).available()) {
                detector.killStaleContainers(runtime, sandboxes.instanceId());
            }
        } catch (RuntimeException e) {
            log.warn("Code Mode container cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private static void deleteQuietly(Path target) {
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                TreeDeleter.delete(target);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete Code Mode images: {}", e.getClass().getSimpleName());
        }
    }
}
