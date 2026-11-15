package com.github.krpkbhdn.ccmanager.toolchain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolCatalogValidatorTest {

    private static final ToolDefinition NODE =
            new ToolDefinition("node", "Node", "Runtimes", ToolKind.PREREQUISITE, "node", List.of(), null, null, null, null, null, null, null);

    private static ToolDefinition server(String id, String prerequisite, List<String> install) {
        return new ToolDefinition(id, id, "g", ToolKind.LANGUAGE_SERVER, id, List.of(), null, prerequisite, null,
                Map.of("linux", install), null, null, null);
    }

    private static List<ToolDefinition> withNode(ToolDefinition... tools) {
        var all = new ArrayList<ToolDefinition>(List.of(NODE));
        all.addAll(List.of(tools));
        return all;
    }

    @Test
    void acceptsBundledSeedAndEmptyInstallLists() {
        assertThatCode(() -> new ToolCatalog()).doesNotThrowAnyException();
        assertThatCode(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of())))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(NODE, NODE))).hasMessageContaining("duplicate id node");
    }

    @Test
    void rejectsUnknownOrNonPrerequisiteReference() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "ghost", List.of()))))
                .hasMessageContaining("unknown prerequisite ghost");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of()), server("b", "a", List.of()))))
                .hasMessageContaining("b: unknown prerequisite a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sudo", "SU", "doas", "runas", "pkexec", "-Verb", "RunAs"})
    void rejectsPrivilegeEscalation(String token) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm", token)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("privilege escalation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a|b", "a&b", "a;b", "a>b", "a<b", "a`b", "$(x)", "a%b", "a^b", "a\"b", "a!b", "a\nb", "a\rb"})
    void rejectsShellMetacharacters(String token) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm", token)))))
                .hasMessageContaining("metacharacter");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/usr/bin/npm", "C:\\bin\\npm", "bin/npm"})
    void rejectsPathAsExecutable(String first) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of(first, "i")))))
                .hasMessageContaining("plain executable name");
    }

    @Test
    void rejectsInstallerOutsideAllowlist() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("curl", "x")))))
                .hasMessageContaining("installer 'curl' is not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sh", "bash", "zsh", "cmd", "powershell", "pwsh", "-c", "-Command"})
    void rejectsShellsAndInterpreters(String token) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm", token)))))
                .hasMessageContaining("shell or interpreter");
    }

    @Test
    void rejectsUnknownInstallKey() {
        var typo = new ToolDefinition("a", "a", "g", ToolKind.LANGUAGE_SERVER, "a", List.of(), null, null, null,
                Map.of("windwos", List.of("npm", "i")), null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(typo))).hasMessageContaining("unknown install key 'windwos'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bin/a", "a\\b", "..", "../a"})
    void rejectsBinaryThatIsNotPlainFileName(String binary) {
        var tool = new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, binary, List.of(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(tool))).hasMessageContaining("binary must be a plain file name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nomarketplace", "A@b", "a@b@c", "a@b c", "a@"})
    void rejectsMalformedLspPlugin(String plugin) {
        var tool = new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, "a", List.of(), plugin, null, null, null, null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(tool))).hasMessageContaining("lspPlugin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm exec x", "npm run x", "go run x", "dotnet new x", "rustup run x", "pip uninstall x", "winget upgrade x"})
    void rejectsVerbOutsideAllowlist(String line) {
        var argv = List.of(line.split(" "));

        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", argv))))
                .hasMessageContaining("may only run");
    }

    @Test
    void rejectsInstallerWithoutVerb() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm")))))
                .hasMessageContaining("may only run 'install'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--registry=http://x", "--PREFIX", "--source", "--index-url", "--extra-index-url", "--userconfig",
            "--globalconfig", "-c", "--config", "--exec", "--"})
    void rejectsDangerousOptions(String option) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm", "install", option)))))
                .hasMessageContaining("forbidden option");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a\tb", "a b", "аbc"})
    void rejectsWhitespaceAndNonAsciiTokens(String token) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(withNode(server("a", "node", List.of("npm", "install", token)))))
                .hasMessageContaining("whitespace or non-ASCII");
    }

    @Test
    void rejectsDangerousVersionArgs() {
        var tool = new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, "a", List.of("--config"), null, null, null, null, null, null, null);
        var spaced = new ToolDefinition("b", "b", "g", ToolKind.PREREQUISITE, "b", List.of("a b"), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(tool))).hasMessageContaining("forbidden option");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(spaced))).hasMessageContaining("whitespace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a$b", "а", "a:b", ""})
    void rejectsBinaryOutsideAllowedCharacters(String binary) {
        var tool = new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, binary, List.of(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(tool))).hasMessageContaining("binary must match");
    }

    @Test
    void rejectsMetacharactersInVersionArgs() {
        var tool = new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, "a", List.of("--v&calc"), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(tool))).hasMessageContaining("versionArgs");
    }

    private static ToolDefinition manual(Map<String, String> manualInstall) {
        return new ToolDefinition("a", "a", "g", ToolKind.PREREQUISITE, "a", List.of(), null, null, null, null, null, manualInstall, null);
    }

    @Test
    void acceptsManualInstallWithSudoAndPipe() {
        var tool = manual(Map.of("linux", "curl -fsSL https://example.org/i.sh | sudo sh"));

        assertThatCode(() -> ToolCatalogValidator.validate(List.of(tool))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownManualInstallKey() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(manual(Map.of("beos", "x")))))
                .hasMessageContaining("unknown manualInstall key 'beos'");
    }

    @Test
    void rejectsMultiLineAndTooLongManualInstall() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(manual(Map.of("linux", "a\nb")))))
                .hasMessageContaining("one non-blank line");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(manual(Map.of("linux", "a\rb")))))
                .hasMessageContaining("one non-blank line");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(manual(Map.of("linux", "x".repeat(301))))))
                .hasMessageContaining("at most 300");
        assertThatCode(() -> ToolCatalogValidator.validate(List.of(manual(Map.of("linux", "x".repeat(300)))))).doesNotThrowAnyException();
    }

    private static final String ARCHIVE = "https://download.eclipse.org/jdtls/{version}/x.tar.gz";

    private static ToolDefinition downloadable(ToolKind kind, List<String> install, String latestUrl, String pattern,
                                               String archiveUrl, ArchiveKind archive, LauncherSpec launcher) {
        var source = new DownloadSource(new LatestSource(LatestSource.Kind.TEXT_FILE, latestUrl, pattern), archiveUrl, archive, launcher);
        return new ToolDefinition("a", "a", "g", kind, "a", List.of(), null, null, null, Map.of("linux", install), null, null,
                Map.of("linux", source));
    }

    private static ToolDefinition download(String latestUrl, String pattern, String archiveUrl, ArchiveKind archive, LauncherSpec launcher) {
        return downloadable(ToolKind.LANGUAGE_SERVER, List.of(), latestUrl, pattern, archiveUrl, archive, launcher);
    }

    private static final String LATEST = "https://download.eclipse.org/jdtls/latest.txt";
    private static final LauncherSpec JDTLS = new LauncherSpec(LauncherSpec.Kind.JDTLS, "");

    @Test
    void acceptsValidDownload() {
        assertThatCode(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, JDTLS))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://download.eclipse.org/a.tar.gz", "https://evil.example/a.tar.gz",
            "https://download.eclipse.org:8443/a.tar.gz", "https://u@download.eclipse.org/a.tar.gz",
            "https://download.eclipse.org/a.tar.gz?v={version}", "https://download.eclipse.org/a.zip"})
    void rejectsBadArchiveUrl(String url) {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", url, ArchiveKind.TAR_GZ, JDTLS))))
                .hasMessageContaining("a download linux");
    }

    @Test
    void rejectsVersionInLatestUrlAndBadPatterns() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download("https://github.com/{version}", "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, JDTLS))))
                .hasMessageContaining("latest.url");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-.+", ARCHIVE, ArchiveKind.TAR_GZ, JDTLS))))
                .hasMessageContaining("exactly one group");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "(", ARCHIVE, ArchiveKind.TAR_GZ, JDTLS))))
                .hasMessageContaining("does not compile");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/bin/x", "../x", "a/../x", "a\\b", "C:/x", "a b", "a;b"})
    void rejectsBadBinaryLauncherPath(String path) {
        var launcher = new LauncherSpec(LauncherSpec.Kind.BINARY, path);
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, launcher))))
                .hasMessageContaining("launcher");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a;b", "a|b", "%x%"})
    void rejectsBadLauncherArgs(String arg) {
        var launcher = new LauncherSpec(LauncherSpec.Kind.BINARY, "bin/x", List.of(arg));
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, launcher))))
                .hasMessageContaining("launcher arg");
    }

    @Test
    void acceptsPlainLauncherArgsAndRejectsThemForJdtls() {
        var plain = new LauncherSpec(LauncherSpec.Kind.BINARY, "bin/x", List.of("--system-path", LauncherSpec.STATE_PLACEHOLDER));
        assertThatCode(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, plain))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ,
                new LauncherSpec(LauncherSpec.Kind.JDTLS, "", List.of("--x")))))).hasMessageContaining("takes no args");
    }

    @Test
    void rejectsJdtlsLauncherWithPathNonServerAndInstallPlusDownload() {
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(download(LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ,
                new LauncherSpec(LauncherSpec.Kind.JDTLS, "bin/x"))))).hasMessageContaining("takes no path");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(downloadable(ToolKind.PREREQUISITE, List.of(), LATEST, "x-(.+)",
                ARCHIVE, ArchiveKind.TAR_GZ, JDTLS)))).hasMessageContaining("only language servers");
        assertThatThrownBy(() -> ToolCatalogValidator.validate(List.of(downloadable(ToolKind.LANGUAGE_SERVER, List.of("npm", "install", "x"),
                LATEST, "x-(.+)", ARCHIVE, ArchiveKind.TAR_GZ, JDTLS)))).hasMessageContaining("same OS");
    }
}
