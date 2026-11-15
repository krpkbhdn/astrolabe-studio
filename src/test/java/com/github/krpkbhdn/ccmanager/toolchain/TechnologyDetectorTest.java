package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TechnologyDetectorTest {

    private static final List<ToolDefinition> TOOLS = List.of(
            tool("typescript", ToolKind.LANGUAGE_SERVER, "", "tsconfig.json", "package.json#react"),
            tool("angular", ToolKind.ADD_ON, "typescript", "angular.json"),
            tool("vue", ToolKind.ADD_ON, "typescript", "package.json#vue"),
            tool("go", ToolKind.LANGUAGE_SERVER, "", "go.mod"),
            tool("rust", ToolKind.LANGUAGE_SERVER, "", "Cargo.toml", "*.rs"),
            tool("kotlin", ToolKind.LANGUAGE_SERVER, "", "build.gradle.kts", "*.kt"),
            tool("csharp", ToolKind.LANGUAGE_SERVER, "", "*.csproj", "*.sln"),
            tool("jdtls", ToolKind.LANGUAGE_SERVER, "", "pom.xml", "build.gradle", "build.gradle.kts", "*.java"),
            tool("node", ToolKind.PREREQUISITE, "", "package.json"));

    private final TechnologyDetector detector = new TechnologyDetector();

    @TempDir
    Path dir;

    @Test
    void typescriptProjectByTsconfig() throws IOException {
        write("tsconfig.json", "{}");
        assertThat(detect()).containsExactly("typescript");
    }

    @Test
    void angularAddOnImpliesTypescript() throws IOException {
        write("angular.json", "{}");
        assertThat(detect()).containsExactlyInAnyOrder("angular", "typescript");
    }

    @Test
    void reactProjectIsDetectedAsTypescript() throws IOException {
        write("package.json", "{\"dependencies\":{\"react\":\"19\"}}");
        assertThat(detect()).containsExactly("typescript");
    }

    @Test
    void vueByDevDependency() throws IOException {
        write("package.json", "{\"devDependencies\":{\"vue\":\"3\"}}");
        assertThat(detect()).contains("vue");
    }

    @Test
    void brokenPackageJsonIsIgnored() throws IOException {
        write("package.json", "{nope");
        assertThat(detect()).isEmpty();
    }

    @Test
    void goRustKotlinAndCsharpMarkers() throws IOException {
        write("go.mod", "");
        write("main.rs", "");
        write("build.gradle.kts", "");
        write("App.csproj", "");
        assertThat(detect()).containsExactlyInAnyOrder("go", "rust", "kotlin", "csharp", "jdtls");
    }

    @Test
    void gradleJavaProjectIsDetectedAsJava() throws IOException {
        write("build.gradle.kts", "");
        write("src/main/java/App.java", "");
        assertThat(detect()).contains("jdtls");
    }

    @Test
    void nestedPackageIsScanned() throws IOException {
        write("packages/web/package.json", "{\"dependencies\":{\"react\":\"19\"}}");
        assertThat(detect()).contains("typescript");
    }

    @Test
    void unknownFoldersAndNodeModulesAreNotScanned() throws IOException {
        write("other/go.mod", "");
        write("packages/node_modules/go.mod", "");
        write("packages/a/b/go.mod", "");
        assertThat(detect()).isEmpty();
    }

    @Test
    void rootMarkerIsFoundInAFolderWithMoreThanFiveHundredEntries() throws IOException {
        for (var i = 0; i < 600; i++) {
            write("file" + i + ".txt", "");
        }
        write("go.mod", "");
        assertThat(detect()).containsExactly("go");
    }

    @Test
    void globMarkerMatchesRegardlessOfCase() throws IOException {
        write("Main.JAVA", "");
        write("lib.RS", "");
        assertThat(detect()).containsExactlyInAnyOrder("jdtls", "rust");
    }

    @Test
    void entriesBeyondTheLimitAreCutByName() throws IOException {
        for (var i = 0; i < 600; i++) {
            write("m%03d.txt".formatted(i), "");
        }
        write("a.rs", "");
        write("z.kt", "");
        assertThat(detect()).containsExactly("rust");
    }

    @Test
    void oversizePackageJsonIsIgnored() throws IOException {
        write("package.json", "{\"dependencies\":{\"react\":\"19\"}}" + " ".repeat(1_100_000));
        assertThat(detect()).isEmpty();
    }

    private java.util.Set<String> detect() {
        return detector.detect(dir, TOOLS);
    }

    private void write(String relative, String content) throws IOException {
        var file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ToolDefinition tool(String id, ToolKind kind, String base, String... markers) {
        return new ToolDefinition(id, id, "g", kind, id, List.of(), "", "", base, List.of(markers), Map.of(), "", Map.of(), null);
    }
}
