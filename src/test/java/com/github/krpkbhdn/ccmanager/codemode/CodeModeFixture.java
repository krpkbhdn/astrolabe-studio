package com.github.krpkbhdn.ccmanager.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.ccmanager.claude.session.SessionRegistry;
import com.github.krpkbhdn.ccmanager.codemode.client.McpClient;
import com.github.krpkbhdn.ccmanager.codemode.client.McpClients;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.ccmanager.codemode.isolation.IsolationSettingsService;
import com.github.krpkbhdn.ccmanager.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.ccmanager.codemode.policy.Decision;
import com.github.krpkbhdn.ccmanager.codemode.policy.ExecutionRegistry;
import com.github.krpkbhdn.ccmanager.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ContainerRuntimeDetector;
import com.github.krpkbhdn.ccmanager.codemode.sandbox.ScriptSandbox;
import com.github.krpkbhdn.ccmanager.config.AppProperties;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpApproval;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpServerDefinition.Transport;
import com.github.krpkbhdn.ccmanager.extension.mcp.McpService;
import com.github.krpkbhdn.ccmanager.project.Project;
import com.github.krpkbhdn.ccmanager.project.ProjectService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared wiring of the Code Mode service tests: mocked configuration, clients and sandbox around the real service, pool and registry. */
abstract class CodeModeFixture {

    static final SessionContext SESSION = new SessionContext(1, "s1");
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    final JsonMapper mapper = JsonMapper.builder().build();
    final ProjectService projects = mock(ProjectService.class);
    final ProjectCodeModeService modes = mock(ProjectCodeModeService.class);
    final McpConfigReader reader = mock(McpConfigReader.class);
    final McpService mcp = mock(McpService.class);
    final McpClients clients = mock(McpClients.class);
    final ToolCallGate gate = mock(ToolCallGate.class);
    final CodeModeAudit audit = mock(CodeModeAudit.class);
    final CodeModeSandboxes sandboxes = mock(CodeModeSandboxes.class);
    final IsolationSettingsService isolation = mock(IsolationSettingsService.class);
    final ContainerRuntimeDetector detector = mock(ContainerRuntimeDetector.class);
    final SessionRegistry sessions = mock(SessionRegistry.class);
    final McpClient alpha = mock(McpClient.class);
    final McpClient beta = mock(McpClient.class);
    final AtomicReference<ScriptSandbox> script = new AtomicReference<>();
    CodeModeService service;
    CodeModeSessionCleanup cleanup;
    ServerConnections connections;
    ServerCandidates candidates;
    ExecutionRegistry executions;
    Path dataDir;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        dataDir = temp;
        var properties = TestProperties.of(Duration.ofSeconds(30), 100_000, 20_000, 10, Duration.ofMinutes(10), "node:22-alpine", "256m", 1,
                Duration.ofDays(30), Duration.ofSeconds(25));
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", temp, Instant.now())));
        when(modes.effective(1)).thenReturn(new ProjectCodeMode(1, true, List.of("alpha", "beta", "gone", "pending"), com.github.krpkbhdn.ccmanager.codemode.ToolCallAccess.ASK));
        when(reader.definitions(temp)).thenReturn(List.of(definition("alpha", ExtensionScope.USER, Transport.STDIO),
                definition("beta", ExtensionScope.USER, Transport.HTTP), definition("pending", ExtensionScope.PROJECT, Transport.STDIO)));
        when(mcp.servers(temp)).thenReturn(List.of(new McpServerConfig("pending", ExtensionScope.PROJECT, "stdio", "x", McpApproval.PENDING)));
        when(clients.create(any())).thenAnswer(call -> "alpha".equals(call.<McpServerDefinition>getArgument(0).name()) ? alpha : beta);
        when(alpha.listTools()).thenReturn(List.of(tool("alpha", "echo")));
        when(beta.listTools()).thenReturn(List.of(tool("beta", "ping")));
        when(isolation.get()).thenReturn(IsolationBackend.GRAALJS);
        when(gate.decide(any(), any(), any(), any())).thenReturn(Decision.AUTO);
        when(sandboxes.forBackend(any())).thenReturn((code, bridge, session) -> script.get().run(code, bridge, session));
        service = serviceWith(properties);
    }

    CodeModeService serviceWith(CodeModeProperties properties) {
        candidates = org.mockito.Mockito.spy(new ServerCandidates(projects, modes, reader, mcp, org.mockito.Mockito.mock(com.github.krpkbhdn.ccmanager.extension.mcp.PluginMcpServers.class)));
        connections = new ServerConnections(candidates, modes, clients, properties, gate, java.time.Clock.systemUTC());
        executions = org.mockito.Mockito.spy(new ExecutionRegistry());
        cleanup = new CodeModeSessionCleanup(sessions, modes, connections, gate, executions, isolation, detector, sandboxes, properties,
                new AppProperties(dataDir));
        return new CodeModeService(modes, connections, gate, audit, executions, sandboxes, isolation, detector, properties, cleanup);
    }

    static McpServerDefinition definition(String name, ExtensionScope scope, Transport transport) {
        return new McpServerDefinition(name, scope, transport, "cmd", List.of(), Map.of(), "", Map.of(), null);
    }

    RemoteTool tool(String server, String name) {
        return RemoteTool.annotated(server, name, "Does " + name, mapper.readTree("{\"type\":\"object\"}"), Optional.empty(), false, false);
    }

    JsonNode json(String text) {
        return mapper.readTree(text);
    }

    void scriptCallingAlphaAndReportingErrors() {
        script.set((code, bridge, session) -> {
            try {
                return ScriptResult.ok(bridge.callTool("alpha", "echo", "{}"), false);
            } catch (RuntimeException e) {
                return ScriptResult.failed(e.getMessage());
            }
        });
    }

    static McpServerDefinition argThat(String name) {
        return org.mockito.ArgumentMatchers.argThat(definition -> definition.name().equals(name));
    }

    static void await(java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    CodeModeProperties scriptTimeout(Duration timeout) {
        return TestProperties.of(timeout, 100_000, 20_000, 10, Duration.ofMinutes(10), "node:22-alpine", "256m", 1, Duration.ofDays(30),
                Duration.ofSeconds(25));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
