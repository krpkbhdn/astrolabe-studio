package com.github.krpkbhdn.astrolabe.codemode.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.codemode.RemoteTool;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ToolSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RemoteTool tool(String server, String name, String description) {
        return RemoteTool.annotated(server, name, description, MAPPER.readTree("{}"), Optional.empty(), false, false);
    }

    private static List<String> names(List<RemoteTool> tools) {
        return tools.stream().map(RemoteTool::name).toList();
    }

    @Test
    void ranksExactThenPrefixThenNameTokenThenDescription() {
        List<RemoteTool> tools = List.of(tool("s", "other", "does click things"), tool("s", "fast_click", "x"),
                tool("s", "click_all", "x"), tool("s", "click", "x"));
        assertThat(names(ToolSearch.rank(tools, "click", 10))).containsExactly("click", "click_all", "fast_click", "other");
    }

    @Test
    void keepsInputOrderOnTies() {
        List<RemoteTool> tools = List.of(tool("s", "b_tool", "x"), tool("s", "a_tool", "x"), tool("s", "c_tool", "x"));
        assertThat(names(ToolSearch.rank(tools, "tool", 10))).containsExactly("b_tool", "a_tool", "c_tool");
    }

    @Test
    void limitsResultsAndDropsNonMatches() {
        List<RemoteTool> tools = List.of(tool("s", "a_x", "x"), tool("s", "b_x", "x"), tool("s", "c_y", "y"));
        assertThat(names(ToolSearch.rank(tools, "x", 1))).hasSize(1);
        assertThat(names(ToolSearch.rank(tools, "zzz", 10))).isEmpty();
    }

    @Test
    void matchesCaseInsensitivelyAndByServer() {
        List<RemoteTool> tools = List.of(tool("github", "Create_Issue", "x"), tool("jira", "create_issue", "x"));
        assertThat(ToolSearch.rank(tools, "GITHUB issue", 10)).first().extracting(RemoteTool::server).isEqualTo("github");
    }

    @Test
    void multipleTokensAccumulate() {
        List<RemoteTool> tools = List.of(tool("s", "list_pages", "x"), tool("s", "close_page", "x"),
                tool("s", "list_close", "x"));
        assertThat(names(ToolSearch.rank(tools, "list close", 10)).get(0)).isEqualTo("list_close");
    }

    @Test
    void blankQueryListsSummariesOnly() {
        List<RemoteTool> tools = List.of(tool("s", "a", "Does A"), tool("s", "b", null));
        assertThat(ToolSearch.search(tools, "  ", 1)).isEqualTo("s.a - Does A\ns.b");
        assertThat(ToolSearch.search(tools, null, 1)).doesNotContain("servers.");
    }

    @Test
    void searchReturnsSignaturesSeparatedByBlankLine() {
        List<RemoteTool> tools = List.of(tool("s", "a", "x"), tool("s", "ab", "x"));
        assertThat(ToolSearch.search(tools, "a", 10)).contains("servers.s.a(").contains("\n\n").contains("servers.s.ab(");
    }
}
