package com.github.krpkbhdn.astrolabe.codemode.catalog;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/** The tools of every connected MCP server; each server's list is replaced as a whole and readers see immutable snapshots. */
public final class ToolCatalog {

    private final int searchLimit;
    private final AtomicReference<Map<String, List<RemoteTool>>> servers = new AtomicReference<>(Map.of());

    public ToolCatalog(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public void put(String server, List<RemoteTool> tools) {
        servers.updateAndGet(current -> with(current, server, List.copyOf(tools)));
    }

    public void remove(String server) {
        servers.updateAndGet(current -> with(current, server, null));
    }

    /** All tools, ordered by server name, then in the order the server listed them. */
    public List<RemoteTool> all() {
        return servers.get().values().stream().flatMap(List::stream).toList();
    }

    public List<RemoteTool> byServer(String server) {
        return servers.get().getOrDefault(server, List.of());
    }

    public String search(String query) {
        return ToolSearch.search(all(), query, searchLimit);
    }

    public Optional<String> signatureOf(String server, String tool) {
        return byServer(server).stream().filter(t -> t.name().equals(tool)).findFirst()
                .map(TypeScriptSignatures::declaration);
    }

    private static Map<String, List<RemoteTool>> with(Map<String, List<RemoteTool>> current, String server,
            List<RemoteTool> tools) {
        Map<String, List<RemoteTool>> copy = new TreeMap<>(current);
        if (tools == null) {
            copy.remove(server);
        } else {
            copy.put(server, tools);
        }
        return Collections.unmodifiableMap(copy);
    }
}
