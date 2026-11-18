package com.github.krpkbhdn.astrolabe.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ParallelTasksTest {

    @Test
    void resultsKeepInputOrderAndRunOnNamedPlatformThreads() {
        var names = ParallelTasks.run(List.of("a", "b"), item -> "task-" + item, item -> Thread.currentThread().getName());

        assertThat(names).containsExactly("task-a", "task-b");
    }

    @Test
    void runtimeFailureOfATaskIsRethrownAsIs() {
        assertThatThrownBy(() -> ParallelTasks.run(List.of(1), item -> "t", item -> { throw new IllegalArgumentException("boom"); }))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
    }

    @Test
    void interruptedCallerCancelsTheWorkersAndKeepsTheInterruptFlag() throws Exception {
        var started = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var callerFlag = new AtomicBoolean();
        var caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                ParallelTasks.run(List.of(1), item -> "stuck", item -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        workerInterrupted.countDown();
                    }
                    return item;
                });
            } catch (IllegalStateException expected) {
                callerFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        caller.interrupt();

        assertThat(workerInterrupted.await(30, TimeUnit.SECONDS)).isTrue();
        caller.join(30_000);
        assertThat(callerFlag).isTrue();
    }
}
