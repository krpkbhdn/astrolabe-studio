package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Independent checks of {@link ReleaseParser} from the update rules: exact names, exact repository URLs, signature discovery. */
class ReleaseParserHostileInputTest {

    private static final ReleaseVersion CURRENT = new ReleaseVersion(1, 0, 58);
    private static final String REPO = "o/r";
    private static final String BASE = "https://github.com/o/r/releases/download/v1.0.59/";
    private static final String DEB = "astrolabe-studio-1.0.59-linux-x64.deb";
    private static final Installation DEB_X64 = new Installation(InstallKind.DEB, "x64", Path.of("root"), Path.of("root", "bin", "app"));
    private static final Installation DMG_ARM = new Installation(InstallKind.DMG, "arm64", Path.of("A.app"), Path.of("A.app", "x"));
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static String asset(String name, String url) {
        return "{\"name\":\"" + name + "\",\"size\":10,\"browser_download_url\":\"" + url + "\"}";
    }

    private static String release(String tag, String... assets) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/o/r/releases/tag/" + tag + "\",\"assets\":[" + String.join(",", assets) + "]}";
    }

    private Optional<AvailableUpdate> parse(String json, Installation installation) {
        return ReleaseParser.newerThan(mapper.readTree(json), CURRENT, installation, REPO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com.evil.example/o/r/releases/download/v1.0.59/" + DEB,
            "https://evil.example/github.com/o/r/releases/download/v1.0.59/" + DEB,
            "http://github.com/o/r/releases/download/v1.0.59/" + DEB,
            "https://github.com/o/r2/releases/download/v1.0.59/" + DEB,
            "https://github.com/o/r/releases/download/v1.0.59/" + DEB + "?x=1",
            "https://github.com/o/r/releases/download/v1.0.59/sub/" + DEB,
            "https://github.com/o/r/releases/download/../../../evil/" + DEB,
            "https://github.com/o/r/releases/download//" + DEB,
            "https://github.com/o/r/releases/download/v1.0.59/%2e%2e/" + DEB,
            "https://github.com/o/r/releases/download/v1.0.59/x y/" + DEB,
            "file:///etc/" + DEB,
            ""})
    void refusesInstallerUrlsThatAreNotAPlainDownloadOfTheConfiguredRepository(String url) {
        var json = release("v1.0.59", asset(DEB, url), asset(DEB + ".sha256", BASE + DEB + ".sha256"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void refusesAChecksumUrlOutsideTheRepositoryEvenWhenTheInstallerIsFine() {
        var json = release("v1.0.59", asset(DEB, BASE + DEB), asset(DEB + ".sha256", "https://evil.example/" + DEB + ".sha256"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void ignoresASignatureAssetWhoseUrlIsForeignAndTreatsTheUpdateAsPageOnly() {
        var json = release("v1.0.59", asset(DEB, BASE + DEB), asset(DEB + ".sha256", BASE + DEB + ".sha256"),
                asset(DEB + ".asc", "https://evil.example/" + DEB + ".asc"));

        assertThat(parse(json, DEB_X64)).hasValueSatisfying(update -> assertThat(update.installable()).isFalse());
    }

    @Test
    void takesTheValidAssetWhenAHostileDuplicateOfTheSameNameComesFirst() {
        var json = release("v1.0.59", asset(DEB, "https://evil.example/" + DEB), asset(DEB, BASE + DEB), asset(DEB + ".sha256", BASE + DEB + ".sha256"), asset(DEB + ".asc", BASE + DEB + ".asc"));

        assertThat(parse(json, DEB_X64)).hasValueSatisfying(update -> assertThat(update.installerUri().toString()).isEqualTo(BASE + DEB));
    }

    @Test
    void shaSignatureNoiseDoesNotCountAsTheInstallerSignature() {
        var json = release("v1.0.59", asset(DEB, BASE + DEB), asset(DEB + ".sha256", BASE + DEB + ".sha256"), asset(DEB + ".sha256.asc", BASE + DEB + ".sha256.asc"));

        assertThat(parse(json, DEB_X64)).hasValueSatisfying(update -> assertThat(update.installable()).isFalse());
    }

    @Test
    void aSignatureOfTheInstallerGivesTheMatchingSignatureUri() {
        var json = release("v1.0.59", asset(DEB, BASE + DEB), asset(DEB + ".sha256", BASE + DEB + ".sha256"), asset(DEB + ".asc", BASE + DEB + ".asc"));

        assertThat(parse(json, DEB_X64)).hasValueSatisfying(update -> {
            assertThat(update.signatureUri().toString()).isEqualTo(BASE + DEB + ".asc");
        });
    }

    @Test
    void aSignatureWithoutTheInstallerOrChecksumGivesNoUpdate() {
        assertThat(parse(release("v1.0.59", asset(DEB + ".asc", BASE + DEB + ".asc"), asset(DEB + ".sha256", BASE + DEB + ".sha256")), DEB_X64)).isEmpty();
        assertThat(parse(release("v1.0.59", asset(DEB + ".asc", BASE + DEB + ".asc"), asset(DEB, BASE + DEB)), DEB_X64)).isEmpty();
    }

    @Test
    void wrongArchitectureOnlyReleaseGivesNoUpdate() {
        var arm = "astrolabe-studio-1.0.59-linux-arm64.deb";
        var json = release("v1.0.59", asset(arm, BASE + arm), asset(arm + ".sha256", BASE + arm + ".sha256"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void macArmInstallationNeverPicksTheX64Dmg() {
        var x64 = "Astrolabe-Studio-1.0.59-x64.dmg";
        var json = release("v1.0.59", asset(x64, BASE + x64), asset(x64 + ".sha256", BASE + x64 + ".sha256"));

        assertThat(parse(json, DMG_ARM)).isEmpty();
    }

    @Test
    void anotherVersionsAssetInTheReleaseIsNotAccepted() {
        var old = "astrolabe-studio-1.0.58-linux-x64.deb";
        var json = release("v1.0.59", asset(old, BASE + old), asset(old + ".sha256", BASE + old + ".sha256"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void releaseWithNoAssetsOrNoAssetsArrayGivesNoUpdate() {
        assertThat(parse(release("v1.0.59"), DEB_X64)).isEmpty();
        assertThat(parse("{\"tag_name\":\"v1.0.59\",\"html_url\":\"https://github.com/o/r\"}", DEB_X64)).isEmpty();
        assertThat(parse("{}", DEB_X64)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.0.59-rc1", "v1.0.59 ; rm -rf", "v1.0.59/../x", "v1.0", "latest", "v99999999999.0.0", "v1.0.59.1", ""})
    void refusesTagsThatAreNotPlainVersions(String tag) {
        var json = release(tag, asset(DEB, BASE + DEB), asset(DEB + ".sha256", BASE + DEB + ".sha256"), asset(DEB + ".asc", BASE + DEB + ".asc"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void hostileAssetNamesNeverMatchAndNeverReachTheResult() {
        var hostile = "astrolabe-studio-1.0.59-linux-x64.deb $(touch pwned) `id` ';";
        var json = release("v1.0.59", asset(hostile, BASE + "x"), asset(hostile + ".sha256", BASE + "x.sha256"));

        assertThat(parse(json, DEB_X64)).isEmpty();
    }

    @Test
    void pageOnlyReleaseNeedsANewerVersionAndFallsBackToTheReleasesList() {
        var newer = mapper.readTree(release("v1.0.59"));
        assertThat(ReleaseParser.pageOnlyNewerThan(newer, CURRENT, "o/r")).hasValueSatisfying(update -> {
            assertThat(update.installable()).isFalse();
            assertThat(update.version()).isEqualTo("1.0.59");
        });
        assertThat(ReleaseParser.pageOnlyNewerThan(mapper.readTree("{\"tag_name\":\"v1.0.59\"}"), CURRENT, "o/r")).hasValueSatisfying(
                update -> assertThat(update.releasePageUri().toString()).isEqualTo("https://github.com/o/r/releases"));
        assertThat(ReleaseParser.pageOnlyNewerThan(mapper.readTree(release("v1.0.58")), CURRENT, "o/r")).isEmpty();
    }
}
