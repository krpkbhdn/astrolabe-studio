package com.github.krpkbhdn.astrolabe.codemode.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.claude.hook.HookProperties;
import com.github.krpkbhdn.astrolabe.claude.proposal.PermissionProposal;
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalAnswer;
import com.github.krpkbhdn.astrolabe.claude.proposal.ProposalService;
import com.github.krpkbhdn.astrolabe.claude.session.SessionProperties;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.claude.session.SessionSnapshot;
import com.github.krpkbhdn.astrolabe.claude.session.TranscriptService;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeProperties;
import com.github.krpkbhdn.astrolabe.codemode.TestProperties;
import com.github.krpkbhdn.astrolabe.codemode.client.McpClients;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeSandboxes;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeService;
import com.github.krpkbhdn.astrolabe.codemode.CodeModeSessionCleanup;
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeMode;
import com.github.krpkbhdn.astrolabe.codemode.ProjectCodeModeService;
import com.github.krpkbhdn.astrolabe.codemode.ServerConnections;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationSettingsService;
import com.github.krpkbhdn.astrolabe.codemode.policy.CodeModeAudit;
import com.github.krpkbhdn.astrolabe.codemode.policy.ExecutionRegistry;
import com.github.krpkbhdn.astrolabe.codemode.policy.ToolCallGate;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerRuntimeDetector;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.extension.ExtensionScope;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpApproval;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpConfigReader;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerConfig;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerDefinition;
import com.github.krpkbhdn.astrolabe.extension.mcp.McpService;
import com.github.krpkbhdn.astrolabe.project.Project;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared wiring of the end-to-end tests: the JSON-RPC endpoint down to a real GraalJS script talking to a real stdio MCP server (a small Node process). */
abstract class EndToEndFixture {

    static final String FAKE_SERVER = """
            const rl = require('readline').createInterface({ input: process.stdin });
            const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
            const ro = { readOnlyHint: true, destructiveHint: false };
            const tools = [
              { name: 'add', description: 'Adds two numbers', inputSchema: { type: 'object', properties: { a: { type: 'number' }, b: { type: 'number' } }, required: ['a', 'b'] }, annotations: ro },
              { name: 'write_file', description: 'Writes something', inputSchema: { type: 'object', properties: { path: { type: 'string' } } } },
              { name: 'shot', description: 'Screenshot', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'hang', description: 'Never answers', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'big', description: 'Huge result', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'fail', description: 'Reports a tool error', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'count', description: 'Counts calls', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'sleep', description: 'Answers after ms', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'pid', description: 'Process id', inputSchema: { type: 'object' }, annotations: ro },
              { name: 'peek', description: 'Reads one thing', inputSchema: { type: 'object' }, annotations: { readOnlyHint: true } },
            ];
            let calls = 0;
            const text = (t) => ({ content: [{ type: 'text', text: t }] });
            rl.on('line', (line) => {
              const m = JSON.parse(line);
              if (m.id === undefined) return;
              const reply = (result) => send({ jsonrpc: '2.0', id: m.id, result });
              if (m.method === 'initialize') return reply({ protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'fake', version: '1' } });
              if (m.method === 'tools/list') return reply({ tools });
              if (m.method === 'tools/call') {
                const { name, arguments: a } = m.params;
                if (name === 'hang') return void (process.env.HANG_FLAG && require('fs').writeFileSync(process.env.HANG_FLAG, ''));
                if (name === 'sleep') return void setTimeout(() => reply(text('late')), a.ms);
                if (name === 'pid') return reply(text(String(process.pid)));
                if (name === 'add') return reply(text(JSON.stringify({ sum: a.a + a.b })));
                if (name === 'write_file') return reply(text('written ' + a.path));
                if (name === 'shot') return reply({ content: [{ type: 'image', mimeType: 'image/png', data: Buffer.from([0x89, 0x50, 0x4e, 0x47]).toString('base64') }] });
                if (name === 'big') return reply(text('y'.repeat(2000000)));
                if (name === 'fail') return reply({ isError: true, content: [{ type: 'text', text: 'quota exceeded' }] });
                if (name === 'count') return reply(text(String(++calls)));
                if (name === 'peek') return reply(text('peeked'));
              }
              send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'unknown' } });
            });
            """;

    final JsonMapper mapper = JsonMapper.builder().build();
    final SessionRegistry registry = new SessionRegistry(new SessionProperties(Duration.ofMinutes(10), Duration.ofMinutes(10)), mock(TranscriptService.class));
    final ProposalService proposals = new ProposalService(registry, new HookProperties(10, 1024, 500, Duration.ofSeconds(30), 100, 1000, 1000), new com.github.krpkbhdn.astrolabe.claude.proposal.SessionTrust());
    final IsolationSettingsService isolation = mock(IsolationSettingsService.class);
    final ContainerRuntimeDetector detector = mock(ContainerRuntimeDetector.class);
    final CodeModeAudit audit = mock(CodeModeAudit.class);
    CodeModeSandboxes sandboxes;
    CodeModeEndpoint endpoint;
    Thread asker;

    ExecutionRegistry executions;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        this.dir = dir;
        wire(Duration.ofSeconds(20), Duration.ofSeconds(2));
    }

    Path dir;

    void wire(Duration scriptTimeout, Duration callTimeout) throws Exception {
        var node = findNode();
        assumeTrue(node != null, "node is not on the PATH");
        var script = Files.writeString(dir.resolve("fake-mcp.js"), FAKE_SERVER);
        var properties = TestProperties.of(scriptTimeout, 200_000, 20_000, 10, Duration.ofMinutes(10), "node:22-alpine", "256m", 1,
                Duration.ofDays(30), callTimeout);
        var projects = mock(ProjectService.class);
        when(projects.find(1)).thenReturn(Optional.of(new Project(1, "p", dir, Instant.now())));
        var modes = mock(ProjectCodeModeService.class);
        when(modes.effective(1)).thenReturn(new ProjectCodeMode(1, true, List.of("fake"), com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.ASK));
        when(modes.effective(2)).thenReturn(new ProjectCodeMode(2, false, List.of(), com.github.krpkbhdn.astrolabe.codemode.ToolCallAccess.ASK));
        var reader = mock(McpConfigReader.class);
        when(reader.definitions(dir)).thenReturn(List.of(new McpServerDefinition("fake", ExtensionScope.USER, McpServerDefinition.Transport.STDIO,
                node, List.of(script.toString()), Map.of("HANG_FLAG", hangFlag().toString()), "", Map.of(), null)));
        var mcp = mock(McpService.class);
        when(mcp.servers(dir)).thenReturn(List.of(new McpServerConfig("fake", ExtensionScope.USER, "stdio", "node", McpApproval.APPROVED)));
        var clients = newClients(properties, new FakeHostEnvironment(HostEnvironment.OperatingSystem.LINUX, dir));
        var gate = new ToolCallGate(proposals, com.github.krpkbhdn.astrolabe.codemode.policy.PermissionRules.NONE, modes);
        var connections = new ServerConnections(new com.github.krpkbhdn.astrolabe.codemode.ServerCandidates(projects, modes, reader, mcp, Mockito.mock(com.github.krpkbhdn.astrolabe.extension.mcp.PluginMcpServers.class)), modes, clients, properties, gate);
        when(isolation.get()).thenReturn(IsolationBackend.GRAALJS);
        sandboxes = Mockito.spy(new CodeModeSandboxes(properties, mapper, new AppProperties(dir)));
        var sessions = mock(SessionRegistry.class);
        for (var projectId : List.of(1L, 2L)) {
            var snapshot = mock(SessionSnapshot.class);
            when(snapshot.projectId()).thenReturn(projectId);
            when(sessions.find("s" + projectId)).thenReturn(Optional.of(snapshot));
        }
        executions = new ExecutionRegistry();
        var cleanup = new CodeModeSessionCleanup(sessions, modes, connections, gate, executions, isolation, detector, sandboxes, properties, new AppProperties(dir));
        var service = new CodeModeService(modes, connections, gate, audit, executions, sandboxes, isolation, detector, properties, cleanup);
        endpoint = new CodeModeEndpoint(List.of(new ListServersTool(service), new SearchToolsTool(service), new ExecuteTool(service)), sessions, connections, service, mapper);
    }

    @AfterEach
    void tearDown() {
        if (asker != null) {
            asker.interrupt();
        }
    }

    static McpClients newClients(CodeModeProperties properties, HostEnvironment host) throws Exception {
        var constructor = McpClients.class.getDeclaredConstructor(CodeModeProperties.class, HostEnvironment.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties, host);
    }

    static String findNode() {
        var exe = System.getProperty("os.name").toLowerCase().contains("win") ? "node.exe" : "node";
        for (var directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            var candidate = Path.of(directory).resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** Answers every permission question that appears with the answer built from it; what was asked is collected. */
    List<String> answerQuestions(java.util.function.Function<PermissionProposal, ProposalAnswer> answer) {
        var asked = new CopyOnWriteArrayList<String>();
        asker = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    proposals.pending().stream().filter(PermissionProposal.class::isInstance).map(PermissionProposal.class::cast).forEach(proposal -> {
                        asked.add(proposal.toolName() + " | " + proposal.toolInput());
                        proposals.answer(proposal.id(), answer.apply(proposal));
                    });
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return asked;
    }

    JsonNode call(String sessionId, String tool, Map<String, Object> arguments) {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":" + mapper.writeValueAsString(Map.of("name", tool, "arguments", arguments)) + "}";
        return mapper.readTree(endpoint.handle(sessionId, body.getBytes(StandardCharsets.UTF_8)).orElseThrow()).path("result");
    }

    JsonNode callWithId(String sessionId, int id, String code) {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
                + mapper.writeValueAsString(Map.of("name", "execute", "arguments", Map.of("code", code))) + "}";
        return mapper.readTree(endpoint.handle(sessionId, body.getBytes(StandardCharsets.UTF_8)).orElseThrow()).path("result");
    }

    void cancelRequest(String sessionId, int id) {
        var body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + id + "}}";
        assertThat(endpoint.handle(sessionId, body.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    Path hangFlag() {
        return dir.resolve("hung.flag");
    }

    /** Polls until the condition holds, failing after five seconds. */
    static void eventually(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waiting for " + what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    /** Starts execute on its own thread and returns once the script is registered and {@code blocked} says it reached its blocking point. */
    Thread executeInBackground(int id, String code, java.util.concurrent.atomic.AtomicReference<JsonNode> outcome, java.util.function.BooleanSupplier blocked) throws InterruptedException {
        var runner = Thread.ofPlatform().start(() -> outcome.set(callWithId("s1", id, code)));
        eventually(() -> executions.isRunning("s1"), "the script to register");
        eventually(blocked, "the script to block");
        return runner;
    }

    boolean serverIsHanging() {
        return Files.exists(hangFlag());
    }

    JsonNode execute(String code) {
        return call("s1", "execute", Map.of("code", code));
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
}
