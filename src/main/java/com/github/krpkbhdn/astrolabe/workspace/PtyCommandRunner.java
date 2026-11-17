package com.github.krpkbhdn.astrolabe.workspace;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a command attached to a pseudo terminal, for commands that refuse to run without one (claude mcp login).
 * Interrupting the calling thread, or the timeout, kills the process and its children.
 */
public final class PtyCommandRunner {

    private static final int COLUMNS = 1000;
    private static final int ROWS = 40;
    private static final int READ_BUFFER_BYTES = 4096;
    /** Output past this is drained and dropped; the whole text is rendered on every chunk, so the cap also bounds that work. */
    static final int MAX_RAW_CHARS = 1024 * 1024;
    private static final Duration READER_GRACE = Duration.ofSeconds(2);

    private PtyCommandRunner() {}

    /** @param onOutput gets the whole output so far, rendered by {@link TerminalText}, after every chunk */
    public static CommandRunner.Result run(List<String> command, Path directory, Duration timeout, Consumer<String> onOutput) throws IOException {
        return run(command, directory, timeout, Map.of(), onOutput);
    }

    /** @param extraEnvironment overrides inherited variables by name, ignoring case (Windows has Path, callers say PATH) */
    public static CommandRunner.Result run(List<String> command, Path directory, Duration timeout, Map<String, String> extraEnvironment,
                                           Consumer<String> onOutput) throws IOException {
        var environment = mergedEnvironment(System.getenv(), extraEnvironment);
        environment.put("TERM", "xterm-256color");
        var builder = new PtyProcessBuilder(command.toArray(String[]::new))
                .setUseWinConPty(true)
                .setEnvironment(environment)
                .setInitialColumns(COLUMNS)
                .setInitialRows(ROWS);
        if (directory != null) {
            builder.setDirectory(directory.toString());
        }
        PtyProcess process = builder.start();
        var output = new StringBuilder();
        var reader = Thread.ofVirtual().start(() -> read(process.getInputStream(), output, onOutput));
        try {
            var finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                ProcessTree.kill(process);
            }
            reader.join(READER_GRACE);
            return new CommandRunner.Result(finished ? process.exitValue() : -1, snapshot(output), "", !finished);
        } catch (InterruptedException e) {
            ProcessTree.kill(process);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.getFirst(), e);
        }
    }

    static Map<String, String> mergedEnvironment(Map<String, String> inherited, Map<String, String> extra) {
        var merged = new HashMap<>(inherited);
        extra.forEach((name, value) -> {
            merged.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            merged.put(name, value);
        });
        return merged;
    }

    static void read(InputStream stream, StringBuilder output, Consumer<String> onOutput) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        var bytes = ByteBuffer.allocate(READ_BUFFER_BYTES);
        var chars = CharBuffer.allocate(READ_BUFFER_BYTES);
        var raw = new StringBuilder();
        try (stream) {
            int count;
            while ((count = stream.read(bytes.array(), bytes.position(), bytes.remaining())) > 0) {
                bytes.position(bytes.position() + count).flip();
                decoder.decode(bytes, chars, false);
                bytes.compact();
                chars.flip();
                int appended = Math.min(chars.remaining(), Math.max(0, MAX_RAW_CHARS - raw.length()));
                raw.append(chars, 0, appended);
                chars.clear();
                if (appended == 0) {
                    continue;
                }
                var text = TerminalText.render(raw.toString());
                synchronized (output) {
                    output.setLength(0);
                    output.append(text);
                }
                onOutput.accept(text);
            }
        } catch (IOException e) {
            // the process ended; what was read so far is the output
        }
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }
}
