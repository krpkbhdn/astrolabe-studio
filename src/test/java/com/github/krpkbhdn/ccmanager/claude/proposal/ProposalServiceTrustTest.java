package com.github.krpkbhdn.ccmanager.claude.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.krpkbhdn.ccmanager.claude.event.HookEvent;
import com.github.krpkbhdn.ccmanager.claude.event.HookEventType;
import com.github.krpkbhdn.ccmanager.claude.hook.HookProperties;
import com.github.krpkbhdn.ccmanager.claude.session.SessionProperties;
import com.github.krpkbhdn.ccmanager.claude.session.SessionRegistry;
import com.github.krpkbhdn.ccmanager.claude.session.TranscriptService;
import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProposalServiceTrustTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String QUESTION = "{\"tool_name\":\"AskUserQuestion\",\"tool_input\":{\"questions\":[{\"question\":\"q\",\"header\":\"h\","
            + "\"options\":[{\"label\":\"a\",\"description\":\"d\"}],\"multiSelect\":false}]}}";

    private final SessionRegistry registry = new SessionRegistry(new SessionProperties(Duration.ofMinutes(10), Duration.ofMinutes(10)), mock(TranscriptService.class));
    private final SessionTrust trust = new SessionTrust();
    private final ProposalService service = new ProposalService(registry, new HookProperties(10, 1024, 500, Duration.ofSeconds(30), 1000, 1000, 1000), trust);
    private final String session = registry.register(1L);

    @TempDir
    Path folder;

    private HookEvent permission(String json) {
        var request = event(HookEventType.PERMISSION_REQUEST, json);
        registry.on(request);
        return request;
    }

    private HookEvent event(HookEventType type, String json) {
        return new HookEvent(session, type, type.wireName(), MAPPER.readTree(json), Instant.now());
    }

    private static String tool(String name) {
        return "{\"tool_name\":\"" + name + "\",\"tool_input\":{}}";
    }

    private boolean allowedAtOnce(HookEvent event) {
        var answer = service.hold(event);
        return answer.isDone() && answer.join().filter(out -> out.contains("allow")).isPresent();
    }

    @Test
    void askHoldsEveryRequest() {
        assertThat(allowedAtOnce(permission(tool("Read")))).isFalse();
        assertThat(allowedAtOnce(permission(tool("Bash")))).isFalse();
        assertThat(service.pending()).hasSize(2);
    }

    @Test
    void sessionNeverRegisteredAsTrustedAsksLikeAssistantAndGroups() {
        assertThat(trust.of(session).level()).isEqualTo(TrustLevel.ASK);
        assertThat(allowedAtOnce(permission(tool("Write")))).isFalse();
    }

    @Test
    void trustedAllowsReadOnlyToolsWithoutAProposalAndHoldsTheRest() {
        trust.set(session, TrustLevel.TRUSTED, folder);
        assertThat(allowedAtOnce(permission(tool("Grep")))).isTrue();
        assertThat(service.pending()).isEmpty();
        assertThat(allowedAtOnce(permission(tool("Bash")))).isFalse();
        assertThat(service.pending()).hasSize(1);
    }

    @Test
    void anAutoAllowedRequestIsNotPublishedAndAHeldOneIs() {
        trust.set(session, TrustLevel.TRUSTED, folder);
        var published = new java.util.concurrent.atomic.AtomicInteger();

        service.hold(event(HookEventType.PERMISSION_REQUEST, tool("Grep")), published::incrementAndGet);
        assertThat(published).hasValue(0);
        service.hold(event(HookEventType.PERMISSION_REQUEST, tool("Bash")), published::incrementAndGet);
        assertThat(published).hasValue(1);
    }

    @Test
    void autonomousAllowsToolsButQuestionsPlansAndFormsStillGoToTheUser() {
        trust.set(session, TrustLevel.AUTONOMOUS, folder);
        assertThat(allowedAtOnce(permission(tool("Bash")))).isTrue();
        assertThat(service.pending()).isEmpty();

        service.hold(permission(QUESTION));
        service.hold(permission("{\"tool_name\":\"ExitPlanMode\",\"tool_input\":{\"plan\":\"p\"}}"));
        service.hold(event(HookEventType.ELICITATION, "{\"mcp_server_name\":\"jira\",\"message\":\"m\"}"));
        assertThat(service.pending()).hasSize(3);
    }

}
