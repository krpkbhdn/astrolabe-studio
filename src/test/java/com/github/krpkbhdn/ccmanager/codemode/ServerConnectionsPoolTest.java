package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.codemode.client.McpClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** The pool of one session's clients: removal, ended sessions and concurrent connects. */
class ServerConnectionsPoolTest extends CodeModeFixture {

    @Test
    void serverRemovedFromTheProjectMidSessionIsClosedAndRefused() {
        connections.connectAll(SESSION);
        when(modes.effective(1)).thenReturn(new ProjectCodeMode(1, true, List.of("beta"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));

        assertThatThrownBy(() -> connections.client(SESSION, "alpha")).hasMessageContaining("not a Code Mode server");
        verify(alpha).close();
        assertThat(connections.client(SESSION, "beta")).isSameAs(beta);
    }

    @Test
    void closedSessionGetsNoNewPoolAndDoesNotLeakClients() {
        connections.closeSession("s1");

        assertThatThrownBy(() -> connections.client(SESSION, "alpha")).hasMessage(ServerConnections.SESSION_ENDED);
        verify(clients, never()).create(any());
    }

    @Test
    void twoCallersConnectingTheSameServerKeepOneClientAndCloseTheOther() throws Exception {
        var first = mock(McpClient.class);
        var second = mock(McpClient.class);
        org.mockito.Mockito.doReturn(first, second).when(clients).create(any());
        when(first.listTools()).thenReturn(List.of(tool("alpha", "echo")));
        when(second.listTools()).thenReturn(List.of(tool("alpha", "echo")));
        var bothConnecting = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(call -> {
            bothConnecting.countDown();
            bothConnecting.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(first).initialize();
        org.mockito.Mockito.doAnswer(call -> {
            bothConnecting.countDown();
            bothConnecting.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(second).initialize();
        var results = new java.util.concurrent.ConcurrentLinkedQueue<McpClient>();
        var callers = List.of(Thread.ofPlatform().start(() -> results.add(connections.client(SESSION, "alpha"))),
                Thread.ofPlatform().start(() -> results.add(connections.client(SESSION, "alpha"))));
        for (var caller : callers) {
            caller.join(10_000);
        }

        assertThat(results).hasSize(2).containsOnly(results.peek());
        var kept = results.peek();
        verify(kept == first ? second : first).close();
        verify(kept, never()).close();
    }
}
