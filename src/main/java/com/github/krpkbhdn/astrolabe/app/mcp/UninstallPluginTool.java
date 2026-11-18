package com.github.krpkbhdn.astrolabe.app.mcp;

import com.github.krpkbhdn.astrolabe.claude.mcp.McpToolDescriptor;
import com.github.krpkbhdn.astrolabe.extension.plugin.PluginService;
import com.github.krpkbhdn.astrolabe.project.ProjectService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class UninstallPluginTool extends PluginTool {

    UninstallPluginTool(ProjectService projects, PluginService plugins) {
        super(projects, plugins);
    }

    @Override
    public String name() {
        return "uninstall_plugin";
    }

    @Override
    public String description() {
        return "Uninstalls a plugin from a scope.";
    }

    @Override
    public String inputSchema() {
        return pluginSchema();
    }

    @Override
    protected String execute(JsonNode arguments) {
        var id = McpToolDescriptor.requiredText(arguments, "plugin");
        plugins.uninstall(id, scopeOf(arguments), projectDirectory(arguments));
        return "Plugin " + id + " uninstalled.";
    }
}
