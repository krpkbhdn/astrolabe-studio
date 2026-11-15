package com.github.krpkbhdn.ccmanager.toolchain;

import com.github.krpkbhdn.ccmanager.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.ccmanager.workspace.CommandRunner;
import com.github.krpkbhdn.ccmanager.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Detects whether a catalog tool is installed, which version it reports and, for language servers, whether it answers. */
@Component
@EnableConfigurationProperties(ToolchainProperties.class)
public class ToolProbe {

    private static final Logger log = LoggerFactory.getLogger(ToolProbe.class);
    private static final int MAX_VERSION_LENGTH = 100;
    private static final int MAX_DETAIL_LENGTH = 200;
    // shortcut: handshake args live here, move them into tools.json when more servers need args
    private static final Map<String, List<String>> HANDSHAKE_ARGS =
            Map.of("typescript", List.of("--stdio"),
                    "pyright", List.of("--stdio"), "kotlin-lsp", List.of("--stdio"));

    /** Runs a command to completion; the seam that lets tests avoid real processes. */
    @FunctionalInterface
    interface VersionCommand {
        CommandRunner.Result run(List<String> command, Duration timeout, Map<String, String> environment) throws IOException;
    }

    private final ToolCatalog catalog;
    private final ExecutableLookup lookup;
    private final HostEnvironment host;
    private final LspHandshake handshake;
    private final ToolchainProperties properties;
    private final ManagedToolsDirectory managedTools;
    private final VersionCommand versionCommand;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final AtomicLong probeGeneration = new AtomicLong();

    private record Cached(ToolStatus status, Instant at, long generation) {}

    @Autowired
    public ToolProbe(ToolCatalog catalog, ExecutableLookup lookup, HostEnvironment host, LspHandshake handshake,
                     ToolchainProperties properties, ManagedToolsDirectory managedTools) {
        this(catalog, lookup, host, handshake, properties, managedTools,
                (command, timeout, environment) -> CommandRunner.run(command, null, timeout, environment), Clock.systemUTC());
    }

    ToolProbe(ToolCatalog catalog, ExecutableLookup lookup, HostEnvironment host, LspHandshake handshake,
              ToolchainProperties properties, ManagedToolsDirectory managedTools, VersionCommand versionCommand, Clock clock) {
        this.managedTools = managedTools;
        this.catalog = catalog;
        this.lookup = lookup;
        this.host = host;
        this.handshake = handshake;
        this.properties = properties;
        this.versionCommand = versionCommand;
        this.clock = clock;
    }

    /** Returns a status younger than the configured TTL, otherwise probes again. */
    public ToolStatus probe(String toolId) {
        var cached = cache.get(toolId);
        if (cached != null && cached.at().plus(properties.statusTtl()).isAfter(clock.instant())) {
            return cached.status();
        }
        return probeFresh(toolId);
    }

    /** Probes now and refreshes the cache, e.g. right after an install; a probe that started earlier never overwrites it. */
    public ToolStatus probeFresh(String toolId) {
        long generation = probeGeneration.incrementAndGet();
        var status = probeUncached(toolId);
        cache.merge(toolId, new Cached(status, clock.instant(), generation),
                (current, probed) -> current.generation() > probed.generation() ? current : probed);
        return status;
    }

    private ToolStatus probeUncached(String toolId) {
        var tool = catalog.find(toolId).orElseThrow(() -> new IllegalArgumentException("Unknown tool " + toolId));
        var found = lookup.find(tool.binary());
        if (found.isEmpty()) {
            return new ToolStatus(toolId, ToolState.MISSING, "", "");
        }
        var installed = detectVersion(tool, found.get());
        if (installed.state() != ToolState.INSTALLED || tool.kind() != ToolKind.LANGUAGE_SERVER) {
            return installed;
        }
        return handshakeStatus(tool, found.get(), installed);
    }

    /** Statuses of every tool in catalog order; fresh cached ones are reused, the rest are probed in parallel. */
    public List<ToolStatus> probeAll() {
        return probeEach(this::probe);
    }

    /** Probes every tool now and refreshes the cache. */
    public List<ToolStatus> probeAllFresh() {
        return probeEach(this::probeFresh);
    }

    private List<ToolStatus> probeEach(Function<String, ToolStatus> probing) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<ToolStatus>>();
            catalog.all().forEach(tool -> futures.add(executor.submit(() -> probeOrFail(tool.id(), probing))));
            var result = new ArrayList<ToolStatus>();
            for (var future : futures) {
                result.add(await(future));
            }
            return List.copyOf(result);
        }
    }

    private static ToolStatus probeOrFail(String toolId, Function<String, ToolStatus> probing) {
        try {
            return probing.apply(toolId);
        } catch (RuntimeException e) {
            log.debug("Probe of {} failed", toolId, e);
            return new ToolStatus(toolId, ToolState.FAILED, "", "probe failed: " + e.getClass().getSimpleName());
        }
    }

    private static ToolStatus await(Future<ToolStatus> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing tools", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Tool probe failed", e.getCause());
        }
    }

    private ToolStatus detectVersion(ToolDefinition tool, Path binary) {
        if (tool.versionArgs().isEmpty()) {
            return new ToolStatus(tool.id(), ToolState.INSTALLED, managedVersion(tool, binary), "");
        }
        try {
            var result = versionCommand.run(ShimLaunch.command(host.windows(), binary, tool.versionArgs()), properties.versionTimeout(),
                    lookup.environment());
            if (result.succeeded()) {
                return new ToolStatus(tool.id(), ToolState.INSTALLED, firstLine(result.stdout(), result.stderr()), "");
            }
            return versionUnavailable(tool, result.timedOut() ? "version check timed out" : failureDetail(result));
        } catch (ShimLaunch.UnsafeLaunchException e) {
            return versionUnavailable(tool, e.getMessage());
        } catch (IOException e) {
            log.debug("Cannot run {}", binary.getFileName(), e);
            return versionUnavailable(tool, "could not start: " + e.getClass().getSimpleName());
        }
    }

    /** A launcher in the app's tools/bin has no version flag; the version is the downloaded folder's name. */
    private String managedVersion(ToolDefinition tool, Path binary) {
        var parent = binary.toAbsolutePath().normalize().getParent();
        return managedTools.binDirectory().equals(parent) ? managedTools.installedVersion(tool.id()).orElse("") : "";
    }

    private static String failureDetail(CommandRunner.Result result) {
        var line = (result.stdout() + "\n" + result.stderr()).lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        var reason = LspHandshake.sanitize(line, LspHandshake.MAX_REASON_LENGTH);
        return reason.isEmpty() ? "version check exited with " + result.exitCode() : reason;
    }

    private static ToolStatus versionUnavailable(ToolDefinition tool, String detail) {
        return new ToolStatus(tool.id(), ToolState.FAILED, "", detail);
    }

    private ToolStatus handshakeStatus(ToolDefinition tool, Path binary, ToolStatus installed) {
        List<String> command;
        try {
            command = ShimLaunch.command(host.windows(), binary, HANDSHAKE_ARGS.getOrDefault(tool.id(), List.of()));
        } catch (ShimLaunch.UnsafeLaunchException e) {
            return new ToolStatus(tool.id(), ToolState.FAILED, installed.version(), e.getMessage());
        }
        var result = handshake.perform(command, properties.handshakeTimeout(), lookup.environment());
        return switch (result.outcome()) {
            case RESPONDED -> new ToolStatus(tool.id(), ToolState.RESPONDING, installed.version(), "");
            case NO_RESPONSE_IN_TIME -> installed;
            case FAILED -> new ToolStatus(tool.id(), ToolState.FAILED, installed.version(), truncate(result.detail(), MAX_DETAIL_LENGTH));
        };
    }

    private static String firstLine(String stdout, String stderr) {
        return (stdout + "\n" + stderr).lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst()
                .map(line -> truncate(line, MAX_VERSION_LENGTH)).orElse("");
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
