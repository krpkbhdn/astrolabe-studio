package com.github.krpkbhdn.ccmanager.toolchain;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Which groups use their own LSP plugin set instead of the one of their members, and which plugin ids count as LSP plugins. */
@Component
public class GroupLspOverrides {

    private final JdbcClient jdbc;
    private final ToolCatalog catalog;

    public GroupLspOverrides(JdbcClient jdbc, ToolCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public boolean overrides(long groupId) {
        return jdbc.sql("SELECT COUNT(*) FROM group_lsp_override WHERE group_id = ?").param(groupId).query(Integer.class).single() > 0;
    }

    public void set(long groupId, boolean overrides) {
        if (overrides) {
            jdbc.sql("INSERT OR IGNORE INTO group_lsp_override (group_id) VALUES (?)").param(groupId).update();
        } else {
            jdbc.sql("DELETE FROM group_lsp_override WHERE group_id = ?").param(groupId).update();
        }
    }

    public Set<String> lspPluginIds() {
        return catalog.all().stream().map(ToolDefinition::lspPlugin).filter(id -> !id.isEmpty()).collect(Collectors.toSet());
    }
}
