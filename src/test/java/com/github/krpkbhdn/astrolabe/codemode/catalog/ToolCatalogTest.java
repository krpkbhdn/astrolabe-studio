package com.github.krpkbhdn.astrolabe.codemode.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ToolCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RemoteTool tool(String server, String name) {
        return RemoteTool.annotated(server, name, "desc " + name, MAPPER.readTree("{}"), Optional.empty(), false, false);
    }

    @Test
    void putReplacesTheWholeServer() {
        ToolCatalog catalog = new ToolCatalog(10);
        catalog.put("a", List.of(tool("a", "one"), tool("a", "two")));
        catalog.put("a", List.of(tool("a", "three")));
        assertThat(catalog.byServer("a")).extracting(RemoteTool::name).containsExactly("three");
    }

    @Test
    void allIsOrderedByServerName() {
        ToolCatalog catalog = new ToolCatalog(10);
        catalog.put("b", List.of(tool("b", "x")));
        catalog.put("a", List.of(tool("a", "y")));
        assertThat(catalog.all()).extracting(RemoteTool::server).containsExactly("a", "b");
    }

    @Test
    void removeDropsAServerAndUnknownServerIsEmpty() {
        ToolCatalog catalog = new ToolCatalog(10);
        catalog.put("a", List.of(tool("a", "x")));
        catalog.remove("a");
        assertThat(catalog.all()).isEmpty();
        assertThat(catalog.byServer("none")).isEmpty();
    }

    @Test
    void snapshotsAreImmutableAndIndependentOfTheSource() {
        ToolCatalog catalog = new ToolCatalog(10);
        List<RemoteTool> source = new ArrayList<>(List.of(tool("a", "x")));
        catalog.put("a", source);
        source.clear();
        List<RemoteTool> snapshot = catalog.all();
        catalog.put("a", List.of(tool("a", "y")));
        assertThat(snapshot).extracting(RemoteTool::name).containsExactly("x");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> snapshot.add(tool("a", "z")));
    }

    @Test
    void searchUsesTheConfiguredLimit() {
        ToolCatalog catalog = new ToolCatalog(1);
        catalog.put("a", List.of(tool("a", "find_one"), tool("a", "find_two")));
        assertThat(catalog.search("find")).contains("find_one").doesNotContain("find_two");
    }

    @Test
    void signatureOfFindsATool() {
        ToolCatalog catalog = new ToolCatalog(10);
        catalog.put("a", List.of(tool("a", "x")));
        assertThat(catalog.signatureOf("a", "x")).hasValueSatisfying(s -> assertThat(s).contains("servers.a.x("));
        assertThat(catalog.signatureOf("a", "missing")).isEmpty();
        assertThat(catalog.signatureOf("none", "x")).isEmpty();
    }

    @Test
    void concurrentPutsKeepEveryServer() throws InterruptedException {
        ToolCatalog catalog = new ToolCatalog(10);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String server = "s" + i;
            threads.add(Thread.ofPlatform().start(() -> catalog.put(server, List.of(tool(server, "t")))));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(catalog.all()).hasSize(20);
    }
}
