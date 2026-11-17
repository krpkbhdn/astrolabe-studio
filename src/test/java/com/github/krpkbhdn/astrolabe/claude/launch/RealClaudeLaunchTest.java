package com.github.krpkbhdn.astrolabe.claude.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.AstrolabeApplication;
import com.github.krpkbhdn.astrolabe.claude.hook.ActivityEntry;
import com.github.krpkbhdn.astrolabe.claude.hook.HookEventStore;
import com.github.krpkbhdn.astrolabe.claude.session.AgentStatus;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launches the real claude TUI through the whole stack; sends no prompt, so no tokens are used. */
class RealClaudeLaunchTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dataDir;

    @Test
    void sessionStartsReportsHooksAndEndsOnExit() throws Exception {
        assumeTrue("1".equals(System.getenv("ASTROLABE_REAL_CLAUDE")));
        try (var context = AstrolabeApplication.start(new String[] {"--astrolabe.data-dir=" + dataDir})) {
            var project = context.getBean(ProjectService.class).add(Path.of("").toAbsolutePath());
            var registry = context.getBean(SessionRegistry.class);
            var session = context.getBean(ClaudeLauncher.class).launch(project.id(), LaunchMode.NEW);

            await(() -> registry.find(session.astrolabeSessionId()).map(s -> s.claudeSessionId() != null).orElse(false));
            List<ActivityEntry> log = context.getBean(HookEventStore.class).recent(session.astrolabeSessionId(), 50);
            System.out.println("STATUS=" + registry.projectStatus(project.id()) + " ACTIVITY=" + log);
            assertThat(registry.projectStatus(project.id())).isEqualTo(AgentStatus.IDLE);

            session.process().getOutputStream().write("/exit\r".getBytes(StandardCharsets.UTF_8));
            session.process().getOutputStream().flush();
            await(() -> registry.find(session.astrolabeSessionId()).isEmpty());
            assertThat(registry.projectStatus(project.id())).isEqualTo(AgentStatus.OFFLINE);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condition within " + WAIT).isTrue();
    }
}
