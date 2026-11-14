package com.github.krpkbhdn.ccmanager.codemode.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InFlightRequestsTest {

    private final InFlightRequests requests = new InFlightRequests();
    private final AtomicInteger cancelled = new AtomicInteger();

    @Test
    void cancelRunsTheActionOnceForTheSameSessionAndId() {
        requests.begin("s1", "1", cancelled::incrementAndGet);

        requests.cancel("s2", "1");
        requests.cancel("s1", "\"1\"");
        requests.cancel("s1", "1");
        requests.cancel("s1", "1");

        assertThat(cancelled).hasValue(1);
        assertThat(requests.size()).isZero();
    }

    @Test
    void finishedRequestIsForgottenAndCannotBeCancelled() {
        requests.begin("s1", "1", cancelled::incrementAndGet).run();

        requests.cancel("s1", "1");

        assertThat(cancelled).hasValue(0);
        assertThat(requests.size()).isZero();
    }

    @Test
    void trackingIsBoundedAndTheOverflowRequestIsJustNotCancellable() {
        for (var i = 0; i < InFlightRequests.MAX_TRACKED + 10; i++) {
            requests.begin("s1", String.valueOf(i), cancelled::incrementAndGet);
        }

        assertThat(requests.size()).isEqualTo(InFlightRequests.MAX_TRACKED);
        requests.cancel("s1", String.valueOf(InFlightRequests.MAX_TRACKED + 5));
        assertThat(cancelled).hasValue(0);
    }
}
