package com.github.krpkbhdn.ccmanager.codemode.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionRegistryTest {

    static final java.util.function.LongSupplier NEVER = () -> Long.MAX_VALUE;

    ExecutionRegistry registry = new ExecutionRegistry();

    @Test
    void cancelInvokesTheRegisteredCancel() {
        var cancelled = new AtomicInteger();
        registry.register("s1", NEVER, cancelled::incrementAndGet);

        assertThat(registry.isRunning("s1")).isTrue();
        assertThat(registry.cancel("s1")).isTrue();
        assertThat(cancelled).hasValue(1);
        assertThat(registry.cancel("other")).isFalse();
        assertThat(registry.runningSessionIds()).containsExactly("s1");
    }

    @Test
    void closingTheHandleUnregistersAndNotifies() {
        var changes = new AtomicInteger();
        registry.subscribe(changes::incrementAndGet);

        try (var handle = registry.register("s1", NEVER, () -> { })) {
            assertThat(registry.isRunning("s1")).isTrue();
        }

        assertThat(registry.isRunning("s1")).isFalse();
        assertThat(changes).hasValue(2);
    }

    @Test
    void secondScriptInTheSameSessionIsRejected() {
        registry.register("s1", NEVER, () -> { });

        assertThatThrownBy(() -> registry.register("s1", NEVER, () -> { })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
        assertThat(registry.register("s2", NEVER, () -> { })).isNotNull();
    }

    @Test
    void secondScriptWhileTheFirstWaitsForAnApprovalIsToldWhichOneAndNothingIsCancelled() {
        var cancelled = new AtomicInteger();
        registry.register("s1", NEVER, cancelled::incrementAndGet, () -> java.util.Optional.of("jira.create_issue"));

        assertThatThrownBy(() -> registry.register("s1", NEVER, () -> { })).isInstanceOf(IllegalStateException.class)
                .hasMessage("A script is waiting for your approval of jira.create_issue in cc-manager: answer or deny the dialog, then retry");
        assertThat(cancelled).hasValue(0);
    }

    @Test
    void staleHandleDoesNotUnregisterANewerScript() {
        var first = registry.register("s1", NEVER, () -> { });
        first.close();
        registry.register("s1", NEVER, () -> { });

        first.close();

        assertThat(registry.isRunning("s1")).isTrue();
    }

    @Test
    void concurrentRegistrationsOfOneSessionAdmitExactlyOne() throws Exception {
        var admitted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.register("s1", NEVER, () -> { });
                        admitted.incrementAndGet();
                    } catch (IllegalStateException rejected) {
                        return null;
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(admitted).hasValue(1);
    }

    @Test
    void scriptPastItsDeadlineAndGraceIsCancelledAndReplaced() {
        var cancelled = new AtomicInteger();
        var overdue = System.nanoTime() - ExecutionRegistry.OVERDUE_GRACE.toNanos() - 1_000_000_000L;
        var stale = registry.register("s1", () -> overdue, cancelled::incrementAndGet);

        var fresh = registry.register("s1", NEVER, () -> { });

        assertThat(cancelled).hasValue(1);
        assertThat(registry.isRunning("s1")).isTrue();
        stale.close();
        assertThat(registry.isRunning("s1")).isTrue();
        fresh.close();
        assertThat(registry.isRunning("s1")).isFalse();
    }

    @Test
    void scriptStillWithinItsDeadlineIsReportedWithAgeAndRemainingTime() {
        var cancelled = new AtomicInteger();
        registry.register("s1", () -> System.nanoTime() + 60_000_000_000L, cancelled::incrementAndGet);

        assertThatThrownBy(() -> registry.register("s1", NEVER, () -> { })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started 0 s ago, limit 60 s").hasMessageContaining("s left").hasMessageContaining("Stop script");
        assertThat(cancelled).hasValue(0);
    }
}
