package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.workspace.ProcessTree;
import com.github.krpkbhdn.astrolabe.workspace.WindowsArgument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/** Checks that a language server binary is alive by running the LSP initialize handshake over its stdio. */
@Component
public class LspHandshake {

    public enum Outcome { RESPONDED, NO_RESPONSE_IN_TIME, FAILED }

    public record Result(Outcome outcome, String detail) {}

    private static final String CONTENT_LENGTH = "content-length:";
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final int INITIALIZE_ID = 1;
    private static final int SHUTDOWN_ID = 2;
    static final int MAX_REASON_LENGTH = 140;
    static final int MAX_STDERR_DETAIL = 200;
    private static final Duration KILL_WAIT = Duration.ofSeconds(5);
    private static final java.util.regex.Pattern ABSOLUTE_PATH = java.util.regex.Pattern.compile(
            "[A-Za-z]:[\\\\/][^\\s\"'`<>|]*|(?<![\\w/])/[^\\s/\"'`]+(?:/[^\\s\"'`]*)+");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public Result perform(List<String> command, Duration timeout) {
        return perform(command, timeout, Map.of());
    }

    /** @param environment set on top of the inherited environment of the server process */
    public Result perform(List<String> command, Duration timeout, Map<String, String> environment) {
        return perform(command, timeout, environment, null);
    }

    /** @param workspace the folder sent as the workspace root, or null for none (a workspace router refuses an initialize without one) */
    public Result perform(List<String> command, Duration timeout, Map<String, String> environment, Path workspace) {
        Process process;
        try {
            WindowsArgument.requireSafeForBatchFile(command);
            var builder = new ProcessBuilder(command);
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IllegalStateException e) {
            return failed("unsafe path for a .cmd launcher");
        } catch (IOException | RuntimeException e) {
            return failed("could not start: " + e.getClass().getSimpleName());
        }
        return performAndStop(process, timeout, workspace);
    }

    Result performAndStop(Process process, Duration timeout) {
        return performAndStop(process, timeout, null);
    }

    /** The whole tree is killed because an npm shim runs the server as a child of cmd.exe. A failure carries the tail of the server's stderr. */
    private Result performAndStop(Process process, Duration timeout, Path workspace) {
        var stderr = StderrTail.drain(process.getErrorStream());
        Result result;
        try {
            result = perform(process.getInputStream(), process.getOutputStream(), timeout, workspace);
        } finally {
            ProcessTree.killAndWait(process, KILL_WAIT);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getOutputStream());
        }
        return result.outcome() == Outcome.FAILED ? failed(result.detail() + stderr.describe()) : result;
    }

    /** Reads a process's stderr in the background so the pipe never fills, keeping only the last characters. */
    private static final class StderrTail implements Runnable {

        private static final int KEPT_CHARACTERS = 2000;
        private static final Duration JOIN_WAIT = Duration.ofSeconds(1);

        private final InputStream stream;
        private final StringBuilder text = new StringBuilder();
        private Thread thread;

        private StderrTail(InputStream stream) {
            this.stream = stream;
        }

        static StderrTail drain(InputStream stream) {
            var tail = new StderrTail(stream);
            tail.thread = new Thread(tail, "lsp-handshake-stderr");
            tail.thread.setDaemon(true);
            tail.thread.start();
            return tail;
        }

        @Override
        public void run() {
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var buffer = new char[512];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    synchronized (text) {
                        text.append(buffer, 0, read);
                        if (text.length() > KEPT_CHARACTERS) {
                            text.delete(0, text.length() - KEPT_CHARACTERS);
                        }
                    }
                }
            } catch (IOException e) {
                // the process was killed; what was read so far is kept
            }
        }

        /** Empty when the server wrote nothing, else "; stderr: ..." with paths replaced, cut to the last {@link #MAX_STDERR_DETAIL} characters. */
        String describe() {
            try {
                thread.join(JOIN_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String snapshot;
            synchronized (text) {
                snapshot = text.toString();
            }
            var clean = sanitize(snapshot, Integer.MAX_VALUE);
            if (clean.isEmpty()) {
                return "";
            }
            return "; stderr: " + (clean.length() > MAX_STDERR_DETAIL ? "..." + clean.substring(clean.length() - MAX_STDERR_DETAIL) : clean);
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // the process is already killed
        }
    }

    Result perform(InputStream fromServer, OutputStream toServer, Duration timeout) {
        return perform(fromServer, toServer, timeout, null);
    }

    Result perform(InputStream fromServer, OutputStream toServer, Duration timeout, Path workspace) {
        var rootUri = workspace == null ? "null" : JSON.writeValueAsString(workspace.toUri().toString());
        var reader = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "lsp-handshake-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":" + INITIALIZE_ID + ",\"method\":\"initialize\","
                    + "\"params\":{\"processId\":null,\"rootUri\":" + rootUri + ",\"capabilities\":{}}}");
            Result result = await(reader, fromServer, timeout);
            if (result.outcome() == Outcome.RESPONDED) {
                send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
                send(toServer, "{\"jsonrpc\":\"2.0\",\"id\":" + SHUTDOWN_ID + ",\"method\":\"shutdown\"}");
                send(toServer, "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
            }
            return result;
        } catch (IOException e) {
            return failed("could not talk to the server: " + e.getClass().getSimpleName());
        } finally {
            reader.shutdownNow();
        }
    }

    private Result await(ExecutorService reader, InputStream fromServer, Duration timeout) {
        Future<Result> response = reader.submit(() -> readInitializeResponse(fromServer));
        try {
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            response.cancel(true);
            return new Result(Outcome.NO_RESPONSE_IN_TIME, "no initialize response in " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("interrupted");
        } catch (ExecutionException e) {
            return failed("unreadable response: " + e.getCause().getClass().getSimpleName());
        }
    }

    private static Result readInitializeResponse(InputStream in) throws IOException {
        while (true) {
            var message = JSON.readTree(readFrame(in));
            if (message.has("method") || !message.path("id").canConvertToInt() || message.path("id").asInt() != INITIALIZE_ID) {
                continue;
            }
            if (message.has("error")) {
                var reason = sanitize(message.path("error").path("message").asString(""), MAX_REASON_LENGTH);
                return failed("server answered initialize with an error" + (reason.isEmpty() ? "" : ": " + reason));
            }
            JsonNode capabilities = message.path("result").path("capabilities");
            return capabilities.isObject()
                    ? new Result(Outcome.RESPONDED, "initialize answered")
                    : failed("initialize response has no capabilities");
        }
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            var line = readLine(in);
            if (line.isEmpty() && length >= 0) {
                break;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith(CONTENT_LENGTH)) {
                length = parseLength(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }
        return in.readNBytes(length);
    }

    private static int parseLength(String text) throws IOException {
        try {
            int length = Integer.parseInt(text);
            if (length < 0 || length > MAX_MESSAGE_BYTES) {
                throw new IOException("bad Content-Length");
            }
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("bad Content-Length", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) {
                throw new IOException("server closed its output");
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, String json) throws IOException {
        var body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Collapses whitespace, replaces absolute paths by {@code <path>} so no user paths reach the UI, then cuts to {@code max}. */
    static String sanitize(String text, int max) {
        var clean = ABSOLUTE_PATH.matcher(text.replaceAll("\\s+", " ").strip()).replaceAll("<path>");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static Result failed(String detail) {
        return new Result(Outcome.FAILED, detail);
    }
}
