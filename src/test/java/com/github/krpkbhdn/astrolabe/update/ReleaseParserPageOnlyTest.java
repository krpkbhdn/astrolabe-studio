package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** A release with the installer and checksum but no {@code .asc} is a page link for every kind of installation; with it, it installs. */
class ReleaseParserPageOnlyTest {

    private static final ReleaseVersion CURRENT = new ReleaseVersion(1, 0, 58);
    private static final String BASE = "https://github.com/o/r/releases/download/v1.0.59/";
    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<Arguments> installations() {
        return Stream.of(InstallKind.values()).flatMap(kind -> Stream.of("x64", "arm64").map(arch -> Arguments.of(kind, arch)));
    }

    private String release(String htmlUrl, String... names) {
        var assets = new StringBuilder();
        for (var name : names) {
            assets.append("{\"name\":\"").append(name).append("\",\"size\":1,\"browser_download_url\":\"").append(BASE).append(name).append("\"},");
        }
        var list = assets.isEmpty() ? "" : assets.substring(0, assets.length() - 1);
        return "{\"tag_name\":\"v1.0.59\",\"html_url\":\"" + htmlUrl + "\",\"assets\":[" + list + "]}";
    }

    @ParameterizedTest
    @MethodSource("installations")
    void withoutAscTheUpdateIsPageOnly(InstallKind kind, String arch) {
        var name = ReleaseParser.assetName(kind, "1.0.59", arch);
        var installation = new Installation(kind, arch, Path.of("root"), Path.of("root", "app"));

        var found = ReleaseParser.newerThan(mapper.readTree(release("https://github.com/o/r/releases/tag/v1.0.59", name, name + ".sha256")), CURRENT, installation, "o/r");

        assertThat(found).hasValueSatisfying(update -> {
            assertThat(update.installable()).isFalse();
            assertThat(update.installerUri()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("installations")
    void withAscTheUpdateInstallsAndTheSignatureUriIsTheInstallerPlusAsc(InstallKind kind, String arch) {
        var name = ReleaseParser.assetName(kind, "1.0.59", arch);
        var installation = new Installation(kind, arch, Path.of("root"), Path.of("root", "app"));

        var found = ReleaseParser.newerThan(mapper.readTree(release("https://github.com/o/r/releases/tag/v1.0.59", name, name + ".sha256", name + ".asc")), CURRENT, installation, "o/r");

        assertThat(found).hasValueSatisfying(update -> {
            assertThat(update.installable()).isTrue();
            assertThat(update.signatureUri().toString()).isEqualTo(BASE + name + ".asc");
        });
    }

    @ParameterizedTest
    @MethodSource("installations")
    void anAscWithoutTheChecksumGivesNoUpdate(InstallKind kind, String arch) {
        var name = ReleaseParser.assetName(kind, "1.0.59", arch);
        var installation = new Installation(kind, arch, Path.of("root"), Path.of("root", "app"));

        assertThat(ReleaseParser.newerThan(mapper.readTree(release("https://github.com/o/r/releases/tag/v1.0.59", name, name + ".asc")), CURRENT, installation, "o/r")).isEmpty();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://github.com/o/r2/releases/tag/v1.0.59", "https://github.com/o/rx", "http://github.com/o/r/releases", "https://github.com.evil.example/o/r/",
            "https://github.com/o/r/../../evil/x", "javascript:alert(1)", "file:///etc/passwd", ""})
    void aReleasePageOutsideTheRepositoryNeverReachesTheUpdate(String page) {
        var installation = new Installation(InstallKind.MSI, "x64", Path.of("root"), Path.of("root", "app"));

        var found = ReleaseParser.newerThan(mapper.readTree(release(page, "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256")), CURRENT, installation, "o/r");

        assertThat(found).hasValueSatisfying(update -> assertThat(update.releasePageUri().toString()).startsWith("https://github.com/o/r/").doesNotContain(".."));
    }
}
