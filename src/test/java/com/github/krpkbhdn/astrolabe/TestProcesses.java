package com.github.krpkbhdn.astrolabe;

import com.github.krpkbhdn.astrolabe.workspace.ProcessTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded waits on child processes, so a child that never answers fails the test instead of hanging the build. */
public final class TestProcesses {

    public static final Duration LIMIT = Duration.ofSeconds(60);
    /** After the exit the pipes only hold what is left; a grandchild that keeps them open must not stall the test. */
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(5);

    public record Drained(int exitCode, String stdout, String stderr) {}

    private TestProcesses() {}

    /** Closes stdin, drains both streams and waits for the exit; on expiry the process tree is killed and the test fails. */
    public static Drained run(Process process) {
        return run(process, LIMIT);
    }

    public static Drained run(Process process, Duration limit) {
        var executor = pipeReaders();
        try {
            var stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()), executor);
            var stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()), executor);
            process.getOutputStream().close();
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("process " + process.pid() + " did not exit within " + limit);
            }
            return new Drained(process.exitValue(), stdout.get(DRAIN_LIMIT.toMillis(), TimeUnit.MILLISECONDS), stderr.get(DRAIN_LIMIT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new AssertionError("process " + process.pid() + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for process " + process.pid(), e);
        } finally {
            if (process.isAlive()) {
                ProcessTree.kill(process);
            }
            executor.shutdownNow();
        }
    }

    /** Reads a line from a child's stdout; a child that stays silent fails the test (the caller's finally kills it). */
    public static String readLine(BufferedReader reader) {
        var executor = pipeReaders();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, executor).get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("no line from the child within " + LIMIT + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for a line", e);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Platform threads: pipe reads must not depend on the shared virtual-thread scheduler, which a loaded runner can starve. */
    private static ExecutorService pipeReaders() {
        return Executors.newCachedThreadPool(runnable -> Thread.ofPlatform().name("test-pipe").daemon().unstarted(runnable));
    }

    /** A git command that never prompts and never leaves a background daemon (fsmonitor, auto gc) holding the pipes open. */
    public static ProcessBuilder git(String... args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.fsmonitor=false", "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        return builder;
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
