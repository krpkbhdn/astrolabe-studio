package com.github.krpkbhdn.ccmanager.codemode.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.claude.proposal.AppDecision;
import com.github.krpkbhdn.ccmanager.claude.proposal.PermissionProposal;
import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalAnswer;
import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalAnswer.PermissionAllow;
import com.github.krpkbhdn.ccmanager.claude.proposal.ProposalService;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeMode;
import com.github.krpkbhdn.ccmanager.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.ccmanager.codemode.RemoteTool;
import com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolCallGateTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SessionContext SESSION = new SessionContext(1, "s1");

    ProposalService proposals = mock(ProposalService.class);
    ProjectCodeModeService projectModes = mock(ProjectCodeModeService.class);
    ToolCallGate gate = new ToolCallGate(proposals, PermissionRules.NONE, projectModes);

    @BeforeEach
    void projectSettingIsOffByDefault() {
        when(projectModes.get(anyLong())).thenReturn(new ProjectCodeMode(1, true, List.of("db"), ToolCallAccess.ASK));
    }
    private static final McpServerDefinition DEFINITION = definition("db-server");

    private static RemoteTool tool(boolean readOnly, boolean destructive) {
        return RemoteTool.annotated("db", "query", "d", MAPPER.createObjectNode(), Optional.empty(), readOnly, destructive);
    }

    private void answerWith(AppDecision decision) {
        when(proposals.holdForApp(any())).thenReturn(CompletableFuture.completedFuture(decision));
    }

    private static JsonNode args(String json) {
        return MAPPER.readTree(json);
    }

    private Decision decide(RemoteTool tool, JsonNode arguments, SessionContext session) {
        return gate.decide(tool, DEFINITION, arguments, session);
    }

    private static McpServerDefinition definition(String command) {
        return definition(command, "db");
    }

    private static McpServerDefinition definition(String command, String name) {
        return new McpServerDefinition(name, ExtensionScope.USER, McpServerDefinition.Transport.STDIO, command, List.of(), Map.of(), null, Map.of(), null);
    }

    private Decision decideAsk(SessionContext session) {
        return decide(tool(false, false), args("{}"), session);
    }

    @Test
    void readOnlyToolRunsWithoutAsking() {
        var decision = decide(tool(true, false), args("{}"), SESSION);

        assertThat(decision.kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        verify(proposals, never()).holdForApp(any());
    }

    @Test
    void readOnlyButDestructiveToolAsks() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));

        assertThat(decide(tool(true, true), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_USER);
    }

    @Test
    void userDenyDeniesTheCall() {
        answerWith(new AppDecision.Answered(new ProposalAnswer.Denial("no")));

        var decision = decideAsk(SESSION);

        assertThat(decision.kind()).isEqualTo(Decision.Kind.DENIED);
        assertThat(decision.reason()).isEqualTo(ToolCallGate.DENIED);
    }

    @Test
    void timeoutDeniesTheCall() {
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decideAsk(SESSION).reason()).isEqualTo(ToolCallGate.TIMED_OUT);
    }

    @Test
    void releasedOrFullQueueDeniesTheCall() {
        answerWith(AppDecision.Unanswered.RELEASED);
        assertThat(decideAsk(SESSION).reason()).isEqualTo(ToolCallGate.UNAVAILABLE);
        answerWith(AppDecision.Unanswered.TOO_MANY_PENDING);
        assertThat(decideAsk(SESSION).allowed()).isFalse();
    }

    @Test
    void failureWhileAskingDeniesTheCall() {
        when(proposals.holdForApp(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(decideAsk(SESSION).reason()).isEqualTo(ToolCallGate.FAILED);

        org.mockito.Mockito.doReturn(CompletableFuture.failedFuture(new IllegalStateException("boom"))).when(proposals).holdForApp(any());
        assertThat(decideAsk(SESSION).allowed()).isFalse();
    }

    @Test
    void dialogShowsMaskedAndTruncatedArgumentsAndOffersSessionChoice() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        var longText = "x".repeat(ArgumentPreview.MAX_TEXT);

        decide(tool(false, false), args("{\"API_KEY\":\"k1\",\"nested\":{\"password\":\"p\",\"ok\":\"v\"},\"text\":\"" + longText + "\"}"),
                SESSION);

        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals).holdForApp(captor.capture());
        var shown = captor.getValue();
        assertThat(shown.toolName()).isEqualTo("db.query");
        assertThat(shown.ccmSessionId()).isEqualTo("s1");
        assertThat(shown.toolInput().get("API_KEY").asString()).isEqualTo(ArgumentPreview.MASK);
        assertThat(shown.toolInput().get("nested").get("password").asString()).isEqualTo(ArgumentPreview.MASK);
        assertThat(shown.toolInput().get("nested").get("ok").asString()).isEqualTo("v");
        assertThat(shown.toolInput().get("text").asString()).isEqualTo(longText);
        assertThat(shown.suggestions()).extracting(PermissionProposal.Suggestion::description)
                .startsWith("Allow db.query for this session", "Allow ALL tools of db for this session, including ones marked destructive");
        assertThat(shown.toolInput().toString()).doesNotContain("k1");
    }

    private PermissionProposal askedWith(String argumentsJson) {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        decide(tool(false, false), args(argumentsJson), SESSION);
        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals, org.mockito.Mockito.atLeastOnce()).holdForApp(captor.capture());
        return captor.getValue();
    }

    @Test
    void cutStringShowsHowManyCharactersAreHiddenAndOffersNoSessionChoice() {
        var shown = askedWith("{\"text\":\"" + "x".repeat(ArgumentPreview.MAX_TEXT + 50) + "\"}");

        assertThat(shown.toolInput().get("text").asString()).isEqualTo("x".repeat(ArgumentPreview.MAX_TEXT) + ArgumentPreview.moreCharacters(50));
        assertThat(shown.suggestions()).extracting(s -> s.raw().path("type").asString()).containsExactly(ToolCallGate.SERVER_GRANT_TYPE);
    }

    @Test
    void cutObjectAndArrayShowHowManyMembersAreHidden() {
        var many = new StringBuilder("{");
        var items = new StringBuilder("[");
        for (int i = 0; i < ArgumentPreview.MAX_ITEMS + 3; i++) {
            many.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":1");
            items.append(i == 0 ? "" : ",").append(i);
        }
        var shown = askedWith("{\"obj\":" + many + "},\"arr\":" + items + "]}");

        assertThat(shown.toolInput().get("obj").get("...").asString()).isEqualTo("3 more fields");
        assertThat(shown.toolInput().get("arr").get(ArgumentPreview.MAX_ITEMS).asString()).isEqualTo(ArgumentPreview.moreItems(3));
        assertThat(shown.suggestions()).extracting(PermissionProposal.Suggestion::description).startsWith("Allow ALL tools of db for this session, including ones marked destructive");
    }

    @Test
    void callWithHiddenArgumentsAsksEvenWhenTheToolWasGrantedForTheSession() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        answerWith(new AppDecision.Answered(PermissionAllow.once()));

        var cut = decide(tool(false, false), args("{\"text\":\"" + "x".repeat(ArgumentPreview.MAX_TEXT + 1) + "\"}"), SESSION);
        var whole = decide(tool(false, false), args("{\"text\":\"short\"}"), SESSION);

        assertThat(cut.kind()).isEqualTo(Decision.Kind.ALLOWED_USER);
        assertThat(whole.kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        verify(proposals, times(1)).holdForApp(any());
    }

    @Test
    void sessionChoiceInAnAnswerIsIgnoredWhenArgumentsWereHidden() {
        var grant = MAPPER.createObjectNode().put("type", ToolCallGate.SESSION_GRANT_TYPE);
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(grant))));

        decide(tool(false, false), args("{\"text\":\"" + "x".repeat(ArgumentPreview.MAX_TEXT + 1) + "\"}"), SESSION);
        clearInvocations(proposals);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decide(tool(false, false), args("{}"), SESSION).allowed()).isFalse();
    }

    @Test
    void hiddenCharactersInServerAndToolNamesAreReplacedInTheLabelAndNoSessionChoiceIsOffered() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        var spoofed = RemoteTool.annotated("db\u202E", "qu\u2066ery\n\u2028x\u0000", "d", MAPPER.createObjectNode(), Optional.empty(), false, false);

        decide(spoofed, args("{}"), SESSION);

        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals).holdForApp(captor.capture());
        assertThat(captor.getValue().toolName()).isEqualTo("db .qu ery x");
        assertThat(captor.getValue().suggestions()).extracting(s -> s.raw().path("type").asString()).isEmpty();
    }

    @Test
    void sessionGrantInTheAnswerIsIgnoredForANameThatNeededCleaning() {
        var grant = MAPPER.createObjectNode().put("type", ToolCallGate.SESSION_GRANT_TYPE);
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(grant))));
        var spoofed = RemoteTool.annotated("db", "que\u202Ery", "d", MAPPER.createObjectNode(), Optional.empty(), false, false);

        decide(spoofed, args("{}"), SESSION);
        clearInvocations(proposals);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decide(spoofed, args("{}"), SESSION).allowed()).isFalse();
    }

    @Test
    void hiddenCharactersInArgumentStringsAndKeysNeverReachTheDialogAndBlockTheSessionChoice() {
        var shown = askedWith("{\"ke\\u202Ey\":\"va\\u2066l\\nue\"}");

        assertThat(shown.toolInput().toString()).doesNotContain("\u202E").doesNotContain("\u2066").doesNotContain("\\n");
        assertThat(shown.suggestions()).extracting(s -> s.raw().path("type").asString()).containsExactly(ToolCallGate.SERVER_GRANT_TYPE);
    }

    @Test
    void errorsAreNotSwallowedAsAFailedCheck() {
        when(proposals.holdForApp(any())).thenThrow(new OutOfMemoryError("test"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decideAsk(SESSION)).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void sessionChoiceGrantsTheToolForThatSessionOnly() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        decideAsk(new SessionContext(9, "probe"));
        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals).holdForApp(captor.capture());
        var grant = captor.getValue().suggestions().getFirst().raw();
        clearInvocations(proposals);
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(grant))));
        decideAsk(SESSION);

        var again = decideAsk(SESSION);
        var otherSession = decideAsk(new SessionContext(1, "s2"));

        assertThat(again.kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        assertThat(otherSession.kind()).isEqualTo(Decision.Kind.ALLOWED_USER);
        verify(proposals, times(2)).holdForApp(any());
    }

    private static RemoteTool toolOf(String server, String name, boolean destructive) {
        return RemoteTool.annotated(server, name, "d", MAPPER.createObjectNode(), Optional.empty(), false, destructive);
    }

    private static JsonNode marker(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    @Test
    void serverGrantCoversOtherToolsOfThatServerInThatSessionOnly() {
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(marker(ToolCallGate.SERVER_GRANT_TYPE), marker("unknown")))));
        decide(toolOf("db", "query", false), args("{}"), SESSION);
        clearInvocations(proposals);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decide(toolOf("db", "other", false), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        assertThat(decide(toolOf("web", "x", false), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("db", "other", false), args("{}"), new SessionContext(1, "s2")).allowed()).isFalse();
        assertThat(decide(toolOf("db", "wipe", true), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        gate.forgetSession("s1");
        assertThat(decide(toolOf("db", "other", false), args("{}"), SESSION).allowed()).isFalse();
    }

    @Test
    void forgetServerWithoutAnyGrantDoesNothing() {
        gate.forgetServer("s1", "db");
    }

    @Test
    void forgetServerRemovesOnlyThatServersToolAndServerGrants() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        gate.allowServerForSession(SESSION, "db", DEFINITION);
        gate.allowForSession(SESSION, toolOf("web", "x", false), DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        gate.forgetServer("s1", "db");

        assertThat(decide(toolOf("db", "query", false), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("web", "x", false), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void destructiveToolOffersOnlyTheServerGrantAndNoToolGrant() {
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(marker(ToolCallGate.SESSION_GRANT_TYPE)))));

        decide(toolOf("db", "wipe", true), args("{}"), SESSION);
        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals).holdForApp(captor.capture());
        clearInvocations(proposals);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(captor.getValue().suggestions()).hasSize(1);
        assertThat(captor.getValue().suggestions().getFirst().raw().path("type").asString()).isEqualTo(ToolCallGate.SERVER_GRANT_TYPE);
        assertThat(decide(toolOf("db", "wipe", true), args("{}"), SESSION).allowed()).isFalse();
    }

    @Test
    void serverGrantFromADestructiveToolsDialogCoversTheServer() {
        answerWith(new AppDecision.Answered(new PermissionAllow(List.of(marker(ToolCallGate.SERVER_GRANT_TYPE)))));
        decide(toolOf("db", "wipe", true), args("{}"), SESSION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decide(toolOf("db", "drop", true), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void dialogShowsHowManyToolsOfTheServerAreAlreadyAllowed() {
        gate.allowForSession(SESSION, toolOf("db", "a", false), DEFINITION);
        gate.allowForSession(SESSION, toolOf("db", "b", false), DEFINITION);

        var shown = askedWith("{\"text\":\"x\"}");

        assertThat(shown.suggestions().getLast().description()).isEqualTo("Allow ALL tools of db for this session, including ones marked destructive (2 already allowed)");
    }

    @Test
    void allowForSessionAndForgetSession() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        assertThat(decide(tool(false, false), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);

        gate.forgetSession("s1");
        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decide(tool(false, false), args("{}"), SESSION).allowed()).isFalse();
    }

    @Test
    void readOnlyToolWithoutDestructiveHintRunsWithoutAsking() {
        var tool = RemoteTool.annotated("db", "query", "d", MAPPER.createObjectNode(), Optional.empty(), true, null);

        assertThat(decide(tool, args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        verify(proposals, never()).holdForApp(any());
    }

    @Test
    void readOnlyToolDeclaringItselfDestructiveAsks() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        var tool = RemoteTool.annotated("db", "query", "d", MAPPER.createObjectNode(), Optional.empty(), true, true);

        assertThat(decide(tool, args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_USER);
    }

    @Test
    void toolWithoutReadOnlyHintAsksEvenWithoutDestructiveHint() {
        answerWith(AppDecision.Unanswered.TIMED_OUT);
        var tool = RemoteTool.annotated("db", "query", "d", MAPPER.createObjectNode(), Optional.empty(), null, false);

        assertThat(decide(tool, args("{}"), SESSION).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }

    @Test
    void interruptedWaitWithdrawsTheQuestionAndDenies() throws Exception {
        when(proposals.holdForApp(any())).thenReturn(new CompletableFuture<>());
        var outcome = new AtomicReference<Decision>();
        var asker = Thread.ofPlatform().start(() -> outcome.set(decide(tool(false, false), args("{}"), SESSION)));
        verifyEventually(() -> verify(proposals).holdForApp(any()));

        asker.interrupt();
        asker.join(5_000);

        verify(proposals).dismiss(any());
        assertThat(outcome.get().allowed()).isFalse();
    }

    @Test
    void toolGrantDoesNotSurviveAChangedServerDefinition() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(gate.decide(toolOf("db", "query", false), definition("another-command"), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("db", "query", false), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void serverGrantDoesNotSurviveAChangedServerDefinition() {
        gate.allowServerForSession(SESSION, "db", DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(gate.decide(toolOf("db", "wipe", true), definition("another-command"), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("db", "wipe", true), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void forgetServerDropsEveryGrantOfThatServerEvenForTheSameDefinition() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        gate.allowServerForSession(SESSION, "db", DEFINITION);
        gate.allowForSession(SESSION, toolOf("web", "open", false), DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        gate.forgetServer("s1", "db");

        assertThat(decide(toolOf("db", "query", false), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("db", "wipe", true), args("{}"), SESSION).allowed()).isFalse();
        assertThat(decide(toolOf("web", "open", false), args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void toolGrantDoesNotCoverAToolThatNowDeclaresItselfDestructive() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decide(toolOf("db", "query", true), args("{}"), SESSION).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }

    @Test
    void proposalNamesItsOriginAndCapsALongLabel() {
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        decide(toolOf("db", "t".repeat(500), false), args("{}"), SESSION);
        var captor = ArgumentCaptor.forClass(PermissionProposal.class);
        verify(proposals).holdForApp(captor.capture());

        assertThat(captor.getValue().origin()).isEqualTo(PermissionProposal.Origin.CODE_MODE);
        assertThat(captor.getValue().toolName()).hasSizeLessThan(150).endsWith("...");
        assertThat(captor.getValue().suggestions()).extracting(s -> s.raw().path("type").asString()).isEmpty();
    }

    @Test
    void serverGrantAppliesToACallWithHiddenArgumentsButAToolGrantDoesNot() {
        var cut = args("{\"text\":\"" + "x".repeat(ArgumentPreview.MAX_TEXT + 1) + "\"}");
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);
        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decide(toolOf("db", "query", false), cut, SESSION).allowed()).isFalse();

        gate.allowServerForSession(SESSION, "db", DEFINITION);

        assertThat(decide(toolOf("db", "query", false), cut, SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void newlinesAndTabsInArgumentsDoNotCountAsHidden() {
        gate.allowForSession(SESSION, toolOf("db", "query", false), DEFINITION);

        assertThat(decide(toolOf("db", "query", false), args("{\"code\":\"a\\n\\tb\"}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    private Decision decideWithRule(PermissionRules.Verdict verdict, RemoteTool tool, McpServerDefinition definition) {
        var ruled = new ToolCallGate(proposals, (session, names, name) -> verdict, projectModes);
        return ruled.decide(tool, definition, args("{}"), SESSION);
    }

    @Test
    void allowRuleRunsTheCallWithoutAskingEvenForADestructiveTool() {
        assertThat(decideWithRule(PermissionRules.Verdict.ALLOW, toolOf("db", "wipe", true), DEFINITION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        assertThat(decideWithRule(PermissionRules.Verdict.ALLOW, toolOf("db", "query", false), DEFINITION).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        verify(proposals, never()).holdForApp(any());
    }

    @Test
    void denyRuleBlocksEvenAReadOnlyTool() {
        var decision = decideWithRule(PermissionRules.Verdict.DENY, tool(true, false), DEFINITION);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(ToolCallGate.DENIED_BY_RULE);
    }

    @Test
    void undecidedRuleAsksAndAFailingRuleCheckDenies() {
        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decideWithRule(PermissionRules.Verdict.UNDECIDED, toolOf("db", "query", false), DEFINITION).allowed()).isFalse();
        verify(proposals).holdForApp(any());

        var failing = new ToolCallGate(proposals, (session, names, name) -> {
            throw new IllegalStateException("boom");
        }, projectModes);
        assertThat(failing.decide(toolOf("db", "query", false), DEFINITION, args("{}"), SESSION).reason()).isEqualTo(ToolCallGate.FAILED);
    }

    @Test
    void askRuleAsksEvenForAReadOnlyToolAndAGrantedOne() {
        answerWith(new AppDecision.Answered(PermissionAllow.once()));
        gate.allowServerForSession(SESSION, "db", DEFINITION);

        assertThat(decideWithRule(PermissionRules.Verdict.ASK, tool(true, false), DEFINITION).kind()).isEqualTo(Decision.Kind.ALLOWED_USER);
        verify(proposals).holdForApp(any());
    }

    @Test
    void rulesAreAskedForTheSavedNameOfTheServerOnly() {
        var seen = new AtomicReference<String>();
        var ruled = new ToolCallGate(proposals, (session, server, name) -> {
            seen.set(server);
            return PermissionRules.Verdict.ALLOW;
        }, projectModes);

        ruled.decide(toolOf("playwright", "navigate", false), definition("x", "plugin_playwright_playwright"), args("{}"), SESSION);

        assertThat(seen.get()).isEqualTo("plugin_playwright_playwright");
    }

    private static void verifyEventually(Runnable check) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (attempt > 100) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private Decision decideWithProjectSetting(ToolCallAccess access, PermissionRules.Verdict verdict, RemoteTool tool, JsonNode arguments) {
        when(projectModes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("db"), access));
        var ruled = new ToolCallGate(proposals, (session, names, name) -> verdict, projectModes);
        return ruled.decide(tool, DEFINITION, arguments, SESSION);
    }

    @Test
    void projectSettingRunsDestructiveAndCutCallsWithoutAskingAndMarksTheDecision() {
        var cut = args("{\"q\":\"" + "x".repeat(ArgumentPreview.MAX_TEXT + 1) + "\"}");

        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.UNDECIDED, toolOf("db", "wipe", true), args("{}")).kind()).isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.UNDECIDED, toolOf("db", "query", false), cut).kind()).isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
        verify(proposals, never()).holdForApp(any());
    }

    @Test
    void projectSettingLeavesReadOnlyToolsAndAllowRulesAsTheyWere() {
        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.UNDECIDED, tool(true, false), args("{}")).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.ALLOW, toolOf("db", "wipe", true), args("{}")).kind()).isEqualTo(Decision.Kind.ALLOWED_AUTO);
    }

    @Test
    void denyRuleStillBlocksAndAskRuleStillAsksWithTheProjectSettingOn() {
        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.DENY, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();

        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decideWithProjectSetting(ToolCallAccess.ALL, PermissionRules.Verdict.ASK, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }

    @Test
    void withTheProjectSettingOffADestructiveToolIsAskedAbout() {
        answerWith(AppDecision.Unanswered.TIMED_OUT);

        assertThat(decideWithProjectSetting(ToolCallAccess.ASK, PermissionRules.Verdict.UNDECIDED, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }

    @Test
    void theProjectSettingIsReadAtCallTimeSoAChangeReachesRunningSessions() {
        var ruled = new ToolCallGate(proposals, PermissionRules.NONE, projectModes);
        answerWith(AppDecision.Unanswered.TIMED_OUT);
        when(projectModes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("db"), ToolCallAccess.ASK));
        assertThat(ruled.decide(toolOf("db", "wipe", true), DEFINITION, args("{}"), SESSION).allowed()).isFalse();

        when(projectModes.get(1)).thenReturn(new ProjectCodeMode(1, true, List.of("db"), ToolCallAccess.ALL));

        assertThat(ruled.decide(toolOf("db", "wipe", true), DEFINITION, args("{}"), SESSION).kind()).isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
    }

    @Test
    void selectedServerRunsWithoutAskingAndAnotherServerAsks() {
        var chosen = ToolCallAccess.selected(List.of("db"));
        assertThat(decideWithProjectSetting(chosen, PermissionRules.Verdict.UNDECIDED, toolOf("db", "wipe", true), args("{}")).kind()).isEqualTo(Decision.Kind.ALLOWED_PROJECT_SETTING);
        verify(proposals, never()).holdForApp(any());

        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decideWithProjectSetting(ToolCallAccess.selected(List.of("other")), PermissionRules.Verdict.UNDECIDED, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }

    @Test
    void selectedServerStillObeysDenyAndAskRules() {
        var chosen = ToolCallAccess.selected(List.of("db"));
        assertThat(decideWithProjectSetting(chosen, PermissionRules.Verdict.DENY, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();

        answerWith(AppDecision.Unanswered.TIMED_OUT);
        assertThat(decideWithProjectSetting(chosen, PermissionRules.Verdict.ASK, toolOf("db", "wipe", true), args("{}")).allowed()).isFalse();
        verify(proposals).holdForApp(any());
    }
}
