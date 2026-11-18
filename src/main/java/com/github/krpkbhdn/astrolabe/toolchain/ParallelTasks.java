package com.github.krpkbhdn.astrolabe.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

/** Runs one task per item on its own daemon platform thread; results keep the input order. */
final class ParallelTasks {

    private ParallelTasks() {
    }

    /**
     * Platform threads, not the shared virtual-thread scheduler (starvable), and a caller interrupt cancels the workers
     * (an executor's close() would wait for them).
     */
    static <T, R> List<R> run(List<T> items, Function<T, String> threadName, Function<T, R> task) {
        var futures = new ArrayList<FutureTask<R>>();
        try {
            for (var item : items) {
                var future = new FutureTask<>(() -> task.apply(item));
                futures.add(future);
                Thread.ofPlatform().name(threadName.apply(item)).daemon().start(future);
            }
            var results = new ArrayList<R>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for parallel tasks", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }
}
