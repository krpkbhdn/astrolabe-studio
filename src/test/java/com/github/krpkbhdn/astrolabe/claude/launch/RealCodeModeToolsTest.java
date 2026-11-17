package com.github.krpkbhdn.astrolabe.claude.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.krpkbhdn.astrolabe.AstrolabeApplication;
import com.github.krpkbhdn.astrolabe.claude.hook.CodeModeAccess;
import com.github.krpkbhdn.astrolabe.claude.hook.HookSettingsWriter;
import com.github.krpkbhdn.astrolabe.claude.install.ClaudeInstallationService;
import com.github.krpkbhdn.astrolabe.claude.plugin.ClaudePluginWriter;
import com.github.krpkbhdn.astrolabe.claude.session.SessionRegistry;
import com.github.krpkbhdn.astrolabe.project.ModelSelection;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real claude -p (haiku, one prompt per run) with the files a Code Mode launch writes: the session must list the astrolabe-code tools
 * and not the hidden .mcp.json server's tools; without Code Mode there is no astrolabe-code server. shortcut: the project server stays "pending approval" in a fresh folder, so the absence of its tools is not proof of hiding. Needs ASTROLABE_REAL_CLAUDE=1.
 */
class RealCodeModeToolsTest {

    private static final int TIMEOUT_SECONDS = 180;
    private static final String SERVER = """
            const rl = require('readline').createInterface({ input: process.stdin });
            const send = (m) => process.stdout.write(JSON.stringify(m) + '\n');
            rl.on('line', (line) => {
              const m = JSON.parse(line);
              if (m.id === undefined) return;
              if (m.method === 'initialize') return send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'astrolabefake', version: '1' } } });
              if (m.method === 'tools/list') return send({ jsonrpc: '2.0', id: m.id, result: { tools: [{ name: 'addnumbers', description: 'adds', inputSchema: { type: 'object' } }] } });
              send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'unknown' } });
            });
            """;
    private static final String PROMPT = "Do not call any tool. Print the exact names of all your tools that start with mcp__ , one per line, "
            + "each as TOOL=<name>, or the single line NONE if there are none.";

    @TempDir
    Path dataDir;

    @TempDir
    Path projectDir;

    @Test
    void hiddenServerToolsAreGoneAndCodeModeToolsAreThere() throws Exception {
        assumeTrue("1".equals(System.getenv("ASTROLABE_REAL_CLAUDE")));
        var script = Files.writeString(projectDir.resolve("fake-mcp.js"), SERVER);
        Files.writeString(projectDir.resolve(".mcp.json"), "{\"mcpServers\":{\"astrolabefake\":{\"command\":\"node\",\"args\":[\"" + script.toString().replace("\\", "/") + "\"]}}}");
        Files.createDirectories(projectDir.resolve(".claude"));
        Files.writeString(projectDir.resolve(".claude/settings.local.json"), "{\"enableAllProjectMcpServers\":true,\"enabledMcpjsonServers\":[\"astrolabefake\"]}");
        try (var context = AstrolabeApplication.start(new String[] {"--astrolabe.data-dir=" + dataDir})) {
            var project = context.getBean(ProjectService.class).add(projectDir);
            var off = run(context, project.id(), CodeModeAccess.OFF);
            System.out.println("OFF_OUTPUT=" + off);
            var on = run(context, project.id(), new CodeModeAccess(List.of("astrolabefake"), List.of("astrolabefake")));
            System.out.println("ON_OUTPUT=" + on);

            assertThat(off).doesNotContain("mcp__astrolabe-code");
            assertThat(on).contains("mcp__astrolabe-code__execute").doesNotContain("mcp__astrolabefake");
        }
    }

    private static String run(org.springframework.context.ConfigurableApplicationContext context, long projectId, CodeModeAccess access) throws Exception {
        var writer = context.getBean(HookSettingsWriter.class);
        var astrolabeSessionId = context.getBean(SessionRegistry.class).register(projectId);
        var files = access == CodeModeAccess.OFF ? writer.write(astrolabeSessionId) : writer.write(astrolabeSessionId, java.util.Map.of(), access);
        var executable = context.getBean(ClaudeInstallationService.class).current().executable();
        var pluginDir = context.getBean(ClaudePluginWriter.class).directory();
        var command = new ArrayList<>(ClaudeCommand.launchArguments(executable, files, pluginDir, LaunchMode.NEW, ModelSelection.DEFAULT));
        command.addAll(List.of("-p", "--model", "haiku", "--", PROMPT));
        var builder = new ProcessBuilder(command).directory(projectDir(context, projectId).toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(System.getenv(), astrolabeSessionId, writer.sessionToken(astrolabeSessionId)));
        var process = builder.start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return output;
    }

    private static Path projectDir(org.springframework.context.ConfigurableApplicationContext context, long projectId) {
        return context.getBean(ProjectService.class).find(projectId).orElseThrow().path();
    }
}
