package com.github.krpkbhdn.astrolabe.workspace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Kills a process with everything it started; descendants come from the pid because pty4j's processes have no handle of their own. */
public final class ProcessTree {

    private ProcessTree() {}

    public static void kill(Process process) {
        ProcessHandle.of(process.pid()).ifPresent(handle -> handle.descendants().forEach(ProcessHandle::destroyForcibly));
        process.destroyForcibly();
    }

    /** Kills the tree and waits up to {@code timeout} until it is gone, so files it held open can be deleted right after (Windows locks them). */
    public static void killAndWait(Process process, Duration timeout) {
        var handles = ProcessHandle.of(process.pid()).map(handle -> handle.descendants().toList()).orElse(List.of());
        kill(process);
        var exits = new ArrayList<CompletableFuture<?>>(handles.stream().map(ProcessHandle::onExit).toList());
        exits.add(process.onExit());
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // a process that outlives the wait was already told to die
        }
    }
}
