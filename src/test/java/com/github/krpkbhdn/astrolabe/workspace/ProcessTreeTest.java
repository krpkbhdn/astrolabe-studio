package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProcessTreeTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    void killTakesTheChildrenOfAPtyProcessDownWithIt() throws Exception {
        PtyAvailability.assumePtyDeliversOutput();
        var windows = System.getProperty("os.name").startsWith("Windows");
        var command = windows ? new String[] {"cmd", "/c", "ping -n 60 127.0.0.1 >nul"} : new String[] {"sh", "-c", "sleep 60 & wait"};
        var process = new PtyProcessBuilder(command).setUseWinConPty(windows).start();
        try {
            var child = awaitDescendant(process);

            ProcessTree.kill(process);

            assertThat(awaitExit(child)).isTrue();
            assertThat(process.waitFor(WAIT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            process.destroyForcibly();
        }
    }

    private static ProcessHandle awaitDescendant(Process process) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var found = ProcessHandle.of(process.pid()).flatMap(handle -> handle.descendants().filter(ProcessTreeTest::isTheLongRunningChild).findFirst());
            if (found.isPresent()) {
                return found.get();
            }
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        }
        throw new IOException("The child process never appeared");
    }

    /** ConPTY adds conhost.exe beside the real child, so the first descendant is not necessarily ping or sleep. */
    private static boolean isTheLongRunningChild(ProcessHandle handle) {
        return handle.info().command().map(command -> command.toLowerCase().matches(".*[\\\\/](ping|sleep)(\\.exe)?")).orElse(false);
    }

    private static boolean awaitExit(ProcessHandle handle) throws InterruptedException {
        try {
            handle.onExit().get(WAIT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return !handle.isAlive();
        }
    }
}
