package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.extension.mcp.McpServerNames;
import java.util.List;

/**
 * What a Code Mode session hides from the model: [hiddenProjectServers] are .mcp.json servers that are not started at all,
 * [deniedServers] have their tools denied. Names are validated, a hostile .mcp.json name cannot inject a rule.
 */
public record CodeModeAccess(List<String> hiddenProjectServers, List<String> deniedServers) {

    public static final CodeModeAccess OFF = new CodeModeAccess(List.of(), List.of());

    public CodeModeAccess {
        hiddenProjectServers = hiddenProjectServers.stream().filter(McpServerNames::usable).distinct().toList();
        deniedServers = deniedServers.stream().filter(McpServerNames::usable).distinct().toList();
    }
}
