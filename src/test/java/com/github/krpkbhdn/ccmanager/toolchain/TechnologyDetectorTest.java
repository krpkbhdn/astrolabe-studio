package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

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

    private final TechnologyDetector detector = new TechnologyDetector(properties(5, 2000, 500));

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
    void moduleInAnArbitraryFolderAtDepthThreeIsFound() throws IOException {
        write("build.gradle", "");
        write("services/portal/ui/package.json", "{}");
        write("services/portal/ui/angular.json", "{}");
        write("services/portal/ui/tsconfig.json", "{}");
        assertThat(detect()).containsExactlyInAnyOrder("jdtls", "typescript", "angular");
    }

    @Test
    void moduleDeeperThanTheMaximumDepthIsNotFound() throws IOException {
        write("a/b/c/d/e/f/go.mod", "");
        assertThat(detect()).isEmpty();
        write("a/b/c/d/e/go.mod", "");
        assertThat(detect()).containsExactly("go");
    }

    @Test
    void ignoredAndHiddenFoldersAreNeverEntered() throws IOException {
        for (var name : List.of("node_modules", "build", "dist", "target", ".git", ".hidden", "vendor")) {
            write(name + "/go.mod", "");
        }
        assertThat(detect()).isEmpty();
    }

    @Test
    void symlinkedFolderIsNotFollowed() throws IOException {
        write("build/real/go.mod", "");
        try {
            Files.createSymbolicLink(dir.resolve("link"), dir.resolve("build/real"));
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symlinks not available: " + e);
        }
        assertThat(detect()).isEmpty();
    }

    @Test
    void totalFolderCapStopsTheWalkBreadthFirst() throws IOException {
        write("a/go.mod", "");
        write("c/Cargo.toml", "");
        write("a/deep/build.gradle", "");
        var capped = new TechnologyDetector(properties(5, 2, 500));
        assertThat(capped.detect(dir, TOOLS)).containsExactly("go");
    }

    @Test
    void emptyAndMissingDirectoriesDoNotThrow() {
        assertThat(detect()).isEmpty();
        assertThat(detector.detect(dir.resolve("missing"), TOOLS)).isEmpty();
    }

    private static ToolchainProperties properties(int depth, int folders, int entries) {
        var one = Duration.ofSeconds(1);
        var size = DataSize.ofMegabytes(1);
        return new ToolchainProperties(one, one, one, one, one, one, size, size, 1, one, depth, folders, entries);
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
    void entriesBeyondTheLimitAreNotAllRead() throws IOException {
        write("a.rs", "");
        write("b.kt", "");
        var capped = new TechnologyDetector(properties(5, 2000, 1));
        assertThat(capped.detect(dir, TOOLS)).hasSize(1);
    }

    @Test
    void foldersAreNotListedOnceTheQueueIsFull() throws IOException {
        write("a/go.mod", "");
        var capped = new TechnologyDetector(properties(5, 1, 500));
        assertThat(capped.detect(dir, TOOLS)).isEmpty();
    }

    @Test
    void severalPackageJsonMarkersAreAnsweredFromOneFile() throws IOException {
        write("package.json", "{\"dependencies\":{\"react\":\"19\",\"vue\":\"3\"}}");
        assertThat(detect()).containsExactlyInAnyOrder("typescript", "vue");
    }

    @Test
    void oversizePackageJsonIsIgnored() throws IOException {
        write("package.json", "{\"dependencies\":{\"react\":\"19\"}}" + " ".repeat(1_100_000));
        assertThat(detect()).isEmpty();
    }

    @Test
    void gradleRootWithAngularAtDepthThreeFindsAllAndAtMaxPlusOneDoesNot() throws IOException {
        write("build.gradle", "");
        write("x/y/z/package.json", "{}");
        write("x/y/z/angular.json", "{}");
        assertThat(detect()).containsExactlyInAnyOrder("jdtls", "angular", "typescript");
        var shallow = new TechnologyDetector(properties(2, 2000, 500));
        assertThat(shallow.detect(dir, TOOLS)).containsExactly("jdtls");
    }

    @Test
    void folderCapKeepsAShallowModuleWhenDeepSiblingsAreMany() throws IOException {
        write("a/b/c/d/go.mod", "");
        write("z/angular.json", "{}");
        var capped = new TechnologyDetector(properties(5, 3, 500));
        assertThat(capped.detect(dir, TOOLS)).containsExactlyInAnyOrder("angular", "typescript");
    }

    @Test
    void addOnWithoutItsBaseMarkerStillImpliesTheBase() throws IOException {
        write("m/n/angular.json", "{}");
        assertThat(detect()).containsExactlyInAnyOrder("angular", "typescript");
    }

    @Test
    void resultIsTheSameOnRepeatedScans() throws IOException {
        for (var name : List.of("b", "a", "c")) {
            write(name + "/go.mod", "");
        }
        write("a/q/Cargo.toml", "");
        assertThat(detect()).isEqualTo(detect()).containsExactlyInAnyOrder("go", "rust");
    }

    @Test
    void aFileNamedLikeAFolderMarkerIsNotEntered() throws IOException {
        write("node_modules", "x");
        write("f/g/go.mod", "");
        assertThat(detect()).containsExactly("go");
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
