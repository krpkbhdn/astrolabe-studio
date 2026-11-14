package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.claude.session.AgentStatus;
import com.github.krpkbhdn.ccmanager.claude.session.SessionSnapshot;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** What an ended or live session leaves behind: clients, grants, remembered directory and files. */
class CodeModeSessionEndTest extends CodeModeFixture {

    @Test
    void endedSessionClosesClientsForgetsGrantsAndDeletesItsFiles() throws Exception {
        var data = Base64.getEncoder().encodeToString(PNG);
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + data + "\"}]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));
        service.execute(SESSION, "code");
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(sessions).subscribe(callback.capture());
        var snapshot = mock(SessionSnapshot.class);
        when(snapshot.status()).thenReturn(AgentStatus.OFFLINE);
        when(sessions.find("s1")).thenReturn(Optional.of(snapshot));

        callback.getValue().run();

        verify(alpha, org.mockito.Mockito.timeout(5_000)).close();
        verify(gate, org.mockito.Mockito.timeout(5_000)).forgetSession("s1");
        verify(modes, org.mockito.Mockito.timeout(5_000)).forgetDirectory("s1");
        await(() -> !Files.exists(dataDir.resolve("code-mode").resolve("s1")));
    }

    @Test
    void cleanupOfAnEndedSessionDoesNotBlockTheListenerThread() throws Exception {
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[]}"));
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));
        service.execute(SESSION, "code");
        var closing = new CountDownLatch(1);
        var closeMayEnd = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            closing.countDown();
            closeMayEnd.await();
            return null;
        }).when(alpha).close();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(sessions).subscribe(callback.capture());
        when(sessions.find("s1")).thenReturn(Optional.empty());

        callback.getValue().run();
        callback.getValue().run();

        assertThat(closing.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        closeMayEnd.countDown();
        verify(gate, org.mockito.Mockito.timeout(5_000)).forgetSession("s1");
        verify(alpha, times(1)).close();
    }

    @Test
    void rememberedDirectoryOfASessionThatNeverCalledAToolIsForgottenWhenItEnds() {
        when(modes.rememberedSessionIds()).thenReturn(java.util.Set.of("quiet"));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(sessions).subscribe(callback.capture());
        when(sessions.find("quiet")).thenReturn(Optional.empty());

        callback.getValue().run();

        verify(modes, org.mockito.Mockito.timeout(5_000)).forgetDirectory("quiet");
    }

    @Test
    void liveSessionIsKeptWhenSessionsChange() {
        script.set((code, bridge, session) -> ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false));
        when(alpha.callTool(any(), any())).thenReturn(json("{\"content\":[]}"));
        service.execute(SESSION, "code");
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(sessions).subscribe(callback.capture());
        var snapshot = mock(SessionSnapshot.class);
        when(snapshot.status()).thenReturn(AgentStatus.WORKING);
        when(sessions.find("s1")).thenReturn(Optional.of(snapshot));

        callback.getValue().run();

        verify(alpha, never()).close();
        verify(gate, never()).forgetSession(any());
    }
}
