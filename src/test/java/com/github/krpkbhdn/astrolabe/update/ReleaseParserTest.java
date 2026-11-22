package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ReleaseParserTest {

    private static final ReleaseVersion CURRENT = new ReleaseVersion(1, 0, 58);
    private static final String REPO = "o/r";
    private static final String BASE = "https://github.com/o/r/releases/download/v1.0.59/";
    private static final Installation WINDOWS = new Installation(InstallKind.MSI, "x64", Path.of("root"), Path.of("root", "app.exe"));
    private final JsonMapper mapper = JsonMapper.builder().build();

    private String release(String tag, String... assetNames) {
        var urls = new String[assetNames.length * 2];
        for (var i = 0; i < assetNames.length; i++) {
            urls[i * 2] = assetNames[i];
            urls[i * 2 + 1] = BASE + assetNames[i];
        }
        return releaseWithUrls(tag, urls);
    }

    private String releaseWithUrls(String tag, String... nameAndUrl) {
        var assets = new StringBuilder();
        for (var i = 0; i < nameAndUrl.length; i += 2) {
            assets.append("{\"name\":\"").append(nameAndUrl[i]).append("\",\"size\":1234,\"browser_download_url\":\"").append(nameAndUrl[i + 1]).append("\"},");
        }
        var list = assets.isEmpty() ? "" : assets.substring(0, assets.length() - 1);
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/o/r/releases/tag/" + tag + "\",\"assets\":[" + list + "]}";
    }

    private java.util.Optional<AvailableUpdate> parse(String json, Installation installation) {
        return ReleaseParser.newerThan(mapper.readTree(json), CURRENT, installation, REPO);
    }

    @Test
    void offersNewerReleaseWithInstallerAndChecksum() {
        var update = parse(release("v1.0.59", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256", "Astrolabe-Studio-1.0.59.msi.asc"), WINDOWS);

        assertThat(update).hasValueSatisfying(found -> {
            assertThat(found.version()).isEqualTo("1.0.59");
            assertThat(found.installerName()).isEqualTo("Astrolabe-Studio-1.0.59.msi");
            assertThat(found.installerSizeBytes()).isEqualTo(1234);
            assertThat(found.checksumUri().toString()).endsWith("Astrolabe-Studio-1.0.59.msi.sha256");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "MSI,x64,Astrolabe-Studio-1.0.59.msi",
            "DMG,arm64,Astrolabe-Studio-1.0.59-arm64.dmg",
            "DMG,x64,Astrolabe-Studio-1.0.59-x64.dmg",
            "TAR_GZ,arm64,astrolabe-studio-1.0.59-linux-arm64.tar.gz",
            "DEB,x64,astrolabe-studio-1.0.59-linux-x64.deb",
            "RPM,x64,astrolabe-studio-1.0.59-linux-x64.rpm"})
    void picksTheExactAssetOfKindAndArchitecture(InstallKind kind, String arch, String name) {
        var installation = new Installation(kind, arch, Path.of("root"), Path.of("root", "app"));

        var update = parse(release("v1.0.59", "other-1.0.59.bin", name, name + ".sha256", name + ".asc", name + ".sha256.asc"), installation);

        assertThat(update).hasValueSatisfying(found -> {
            assertThat(found.installerName()).isEqualTo(name);
            assertThat(found.signatureUri().toString()).isEqualTo(BASE + name + ".asc");
        });
    }

    @Test
    void ignoresAssetOfTheOtherArchitectureOrKind() {
        var installation = new Installation(InstallKind.DEB, "arm64", Path.of("root"), Path.of("root", "app"));

        assertThat(parse(release("v1.0.59", "astrolabe-studio-1.0.59-linux-x64.deb", "astrolabe-studio-1.0.59-linux-x64.deb.sha256"), installation)).isEmpty();
        assertThat(parse(release("v1.0.59", "astrolabe-studio-1.0.59-linux-arm64.rpm", "astrolabe-studio-1.0.59-linux-arm64.rpm.sha256"), installation)).isEmpty();
    }

    @Test
    void matchesNamesExactlyNotBySuffix() {
        assertThat(parse(release("v1.0.59", "x-Astrolabe-Studio-1.0.59.msi", "x-Astrolabe-Studio-1.0.59.msi.sha256"), WINDOWS)).isEmpty();
    }

    @Test
    void ignoresSameAndOlderVersions() {
        assertThat(parse(release("v1.0.58", "Astrolabe-Studio-1.0.58.msi", "Astrolabe-Studio-1.0.58.msi.sha256"), WINDOWS)).isEmpty();
        assertThat(parse(release("v1.0.9", "Astrolabe-Studio-1.0.9.msi", "Astrolabe-Studio-1.0.9.msi.sha256"), WINDOWS)).isEmpty();
    }

    @Test
    void comparesNumericallyNotAsText() {
        var base = "https://github.com/o/r/releases/download/1.0.100/";
        var json = releaseWithUrls("1.0.100", "Astrolabe-Studio-1.0.100.msi", base + "Astrolabe-Studio-1.0.100.msi",
                "Astrolabe-Studio-1.0.100.msi.sha256", base + "Astrolabe-Studio-1.0.100.msi.sha256",
                "Astrolabe-Studio-1.0.100.msi.asc", base + "Astrolabe-Studio-1.0.100.msi.asc");

        assertThat(parse(json, WINDOWS)).isPresent();
    }

    @Test
    void skipsReleaseWithoutChecksum() {
        assertThat(parse(release("v1.0.59", "Astrolabe-Studio-1.0.59.msi"), WINDOWS)).isEmpty();
        assertThat(parse(release("v1.0.59", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256.asc"), WINDOWS)).isEmpty();
    }


    @Test
    void releaseWithoutSignatureIsOnlyALinkToTheReleasePage() {
        var update = parse(release("v1.0.59", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256"), WINDOWS);

        assertThat(update).hasValueSatisfying(found -> {
            assertThat(found.installable()).isFalse();
            assertThat(found.version()).isEqualTo("1.0.59");
            assertThat(found.releasePageUri().toString()).isEqualTo("https://github.com/o/r/releases/tag/v1.0.59");
        });
    }

    @Test
    void releasePageOutsideTheRepositoryFallsBackToTheReleasesList() {
        var json = release("v1.0.59", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256").replace("https://github.com/o/r/releases/tag/v1.0.59", "https://evil.example/x");

        assertThat(parse(json, WINDOWS)).hasValueSatisfying(found -> assertThat(found.releasePageUri().toString()).isEqualTo("https://github.com/o/r/releases"));
        assertThat(ReleaseParser.pageOnlyNewerThan(mapper.readTree(json), CURRENT, REPO)).hasValueSatisfying(
                found -> assertThat(found.releasePageUri().toString()).isEqualTo("https://github.com/o/r/releases"));
    }

    @Test
    void missingSizeIsReportedAsZeroNotAsNoCap() {
        var json = release("v1.0.59", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256", "Astrolabe-Studio-1.0.59.msi.asc").replace("\"size\":1234,", "");

        assertThat(parse(json, WINDOWS)).hasValueSatisfying(found -> assertThat(found.installerSizeBytes()).isZero());
    }
    @Test
    void skipsUnparsableTag() {
        assertThat(parse(release("nightly", "Astrolabe-Studio-1.0.59.msi", "Astrolabe-Studio-1.0.59.msi.sha256"), WINDOWS)).isEmpty();
    }

    @Test
    void rejectsDownloadUrlsOutsideTheConfiguredRepository() {
        var name = "Astrolabe-Studio-1.0.59.msi";
        for (var url : new String[] {"https://evil.example/o/r/releases/download/v1.0.59/" + name, "http://github.com/o/r/releases/download/v1.0.59/" + name,
                "https://github.com/x/y/releases/download/v1.0.59/" + name, "https://github.com/o/r/releases/download/../../x/y/" + name,
                "https://github.com/o/r/releases/download/v1.0.59/sub/" + name, "https://github.com/o/r/releases/download//" + name}) {
            assertThat(parse(releaseWithUrls("v1.0.59", name, url, name + ".sha256", BASE + name + ".sha256"), WINDOWS)).as(url).isEmpty();
        }
    }
}
