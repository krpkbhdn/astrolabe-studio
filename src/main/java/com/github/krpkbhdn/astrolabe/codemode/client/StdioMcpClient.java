package com.github.krpkbhdn.astrolabe.codemode.client;

import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.ProcessTree;
import com.github.krpkbhdn.astrolabe.workspace.WindowsArgument;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/** An MCP server started as a child process that speaks one JSON-RPC message per line; stderr is drained and dropped. */
final class StdioMcpClient extends JsonRpcMcpClient {

    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private final McpServerDefinition definition;
    private final CodeModeProperties properties;
    private final HostEnvironment host;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Object writeLock = new Object();
    private final ExecutorService writes = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile McpClientException endedBy;
    private volatile boolean closed;

    StdioMcpClient(McpServerDefinition definition, CodeModeProperties properties, HostEnvironment host) {
        super(definition.name());
        this.definition = definition;
        this.properties = properties;
        this.host = host;
    }

    @Override
    public void initialize() {
        start();
        super.initialize();
    }

    /** Only starting is serialized; the handshake wait must not hold a lock the reader (answering server requests) or close needs. */
    private synchronized void start() {
        if (closed) {
            throw new McpClientException(closedMessage());
        }
        if (process != null) {
            return;
        }
        var command = new ArrayList<String>();
        command.add(resolveExecutable(definition.command()));
        command.addAll(definition.args());
        requireSafeForBatchFile(command);
        var builder = new ProcessBuilder(command);
        builder.environment().putAll(definition.env());
        if (definition.workingDirectory() != null) {
            builder.directory(definition.workingDirectory().toFile());
        }
        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            throw new McpClientException("Cannot start the MCP server " + definition.name() + ": the command is missing or not executable");
        }
        stdin = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        process = started;
        Thread.startVirtualThread(() -> drain(started.getErrorStream()));
        Thread.startVirtualThread(() -> readResponses(started));
    }

    private static void requireSafeForBatchFile(List<String> command) {
        try {
            WindowsArgument.requireSafeForBatchFile(command);
        } catch (IllegalStateException e) {
            throw new McpClientException("The MCP server is a .cmd file, which cannot take the characters & | < > ^ % or line breaks in its arguments");
        }
    }

    /** CreateProcess runs only real executables, so a bare name such as npx is looked up on PATH with the PATHEXT extensions (npx.cmd). */
    private String resolveExecutable(String configured) {
        if (!host.windows()) {
            return configured;
        }
        var command = isRelativePath(configured) && definition.workingDirectory() != null
                ? definition.workingDirectory().resolve(configured).toString() : configured;
        if (hasExtension(command)) {
            return command;
        }
        var extensions = host.env("PATHEXT") == null ? DEFAULT_PATHEXT : host.env("PATHEXT");
        var names = List.of(extensions.split(";")).stream().filter(ext -> !ext.isBlank()).map(ext -> command + ext.toLowerCase(Locale.ROOT)).toList();
        var candidates = Path.of(command).isAbsolute() ? names.stream().map(Path::of).toList() : host.pathCandidates(names);
        return candidates.stream().filter(host::isFile).findFirst().map(Path::toString).orElse(command);
    }

    /** A command with a separator that is not absolute; CreateProcess would look it up from the app's directory, not the server's working directory. */
    private static boolean isRelativePath(String command) {
        return (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) && !Path.of(command).isAbsolute();
    }

    private static boolean hasExtension(String command) {
        return command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).contains(".");
    }

    @Override
    JsonNode request(String method, JsonNode params) {
        var id = ids.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            if (endedBy != null) {
                throw endedBy;
            }
            sendBounded(requestMessage(id, method, params));
            return resultOf(future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new McpClientException("The server " + definition.name() + " did not answer " + method + " in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted while waiting for " + method);
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    @Override
    void notifyServer(String method) {
        sendBounded(notificationMessage(method));
    }

    /** A blocking write (a server that stopped reading stdin) gets the call timeout; past it the process is killed so the stuck writer ends. */
    private void sendBounded(JsonNode message) {
        if (closed) {
            throw new McpClientException(closedMessage());
        }
        try {
            var writing = writes.submit(() -> send(message));
            try {
                writing.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                writing.cancel(true);
                kill();
                throw new McpClientException("The server " + definition.name() + " is not accepting input in time");
            } catch (InterruptedException e) {
                writing.cancel(true);
                Thread.currentThread().interrupt();
                throw new McpClientException("Interrupted while sending to the server " + definition.name());
            } catch (ExecutionException e) {
                throw failure(e.getCause());
            }
        } catch (RejectedExecutionException e) {
            throw new McpClientException(closedMessage());
        }
    }

    private String closedMessage() {
        return "The connection to the server " + definition.name() + " is closed";
    }

    private void send(JsonNode message) {
        try {
            synchronized (writeLock) {
                stdin.write(MAPPER.writeValueAsString(message));
                stdin.write('\n');
                stdin.flush();
            }
        } catch (IOException e) {
            throw new McpClientException("The server " + definition.name() + " is not accepting input", e);
        }
    }

    private void readResponses(Process process) {
        var ended = new McpClientException("The server " + definition.name() + " ended");
        try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readBoundedLine(lines)) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            // the process closed its output; pending requests are failed below
        } catch (McpClientException e) {
            ended = e;
            ProcessTree.kill(process);
        }
        var reason = ended;
        endedBy = reason;
        pending.values().forEach(future -> future.completeExceptionally(reason));
    }

    /** A line that is not JSON is server noise on stdout and skipped. */
    private void dispatch(String line) {
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (RuntimeException e) {
            return;
        }
        if (message.has("method") && message.has("id")) {
            declineOrAnswer(message);
        } else if (message.has("id") && !message.has("method")) {
            var future = pending.get(message.path("id").asLong(-1));
            if (future != null) {
                future.complete(message);
            }
        }
    }

    private void declineOrAnswer(JsonNode serverRequest) {
        try {
            sendBounded(replyToServerRequest(serverRequest));
        } catch (McpClientException e) {
            // the server is gone; the reader loop ends on its own
        }
    }

    private static void drain(InputStream stream) {
        try (stream) {
            stream.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            // the process is gone
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        writes.shutdownNow();
        kill();
    }

    private void kill() {
        var running = process;
        if (running != null) {
            ProcessTree.kill(running);
        }
    }
}
