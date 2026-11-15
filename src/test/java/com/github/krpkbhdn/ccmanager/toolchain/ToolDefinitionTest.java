package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDefinitionTest {

    private static ToolDefinition tool(List<String> versionArgs, Map<String, List<String>> install) {
        return new ToolDefinition("t", "Tool", "G", ToolKind.LANGUAGE_SERVER, "t", versionArgs, null, null, null, install, null, null, null);
    }

    @Test
    void nullOptionalFieldsBecomeEmpty() {
        var tool = tool(null, null);

        assertThat(tool.versionArgs()).isEmpty();
        assertThat(tool.markers()).isEmpty();
        assertThat(tool.install()).isEmpty();
        assertThat(tool.lspPlugin()).isEmpty();
        assertThat(tool.prerequisite()).isEmpty();
        assertThat(tool.docsUrl()).isEmpty();
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        assertThatThrownBy(() -> new ToolDefinition(null, "n", "g", ToolKind.LANGUAGE_SERVER, "b", null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void collectionsAreCopiedAndImmutable() {
        var args = new ArrayList<>(List.of("--version"));
        var argv = new ArrayList<>(List.of("npm", "i"));
        var install = new HashMap<String, List<String>>(Map.of("linux", argv));
        var tool = tool(args, install);

        args.add("x");
        argv.add("y");
        install.put("macos", List.of("brew"));

        assertThat(tool.versionArgs()).containsExactly("--version");
        assertThat(tool.install()).containsOnlyKeys("linux");
        assertThat(tool.installCommand("linux")).containsExactly("npm", "i");
        assertThatThrownBy(() -> tool.versionArgs().add("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.install().put("windows", List.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.installCommand("linux").add("z")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void installCommandAndHasInstallCommandPerOs() {
        var tool = tool(null, Map.of("linux", List.of("apt", "install"), "macos", List.of()));

        assertThat(tool.hasInstallCommand("linux")).isTrue();
        assertThat(tool.hasInstallCommand("macos")).isFalse();
        assertThat(tool.hasInstallCommand("windows")).isFalse();
        assertThat(tool.installCommand("windows")).isEmpty();
    }

    @Test
    void manualInstallDropsBlankValuesAndAnswersEmptyWhenAbsent() {
        var tool = new ToolDefinition("t", "Tool", "G", ToolKind.LANGUAGE_SERVER, "t", null, null, null, null, null, null,
                Map.of("linux", "apt install t", "macos", "  "), null);

        assertThat(tool.manualInstall()).containsOnlyKeys("linux");
        assertThat(tool.manualCommand("linux")).isEqualTo("apt install t");
        assertThat(tool.manualCommand("macos")).isEmpty();
        assertThat(tool(null, null).manualInstall()).isEmpty();
    }
}
