package com.github.krpkbhdn.astrolabe.update;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads a GitHub release and picks the exact asset for this installation. A release without that installer and its
 * {@code .sha256} is skipped; one without the {@code .asc} signature is only a link to the release page.
 */
final class ReleaseParser {

    private static final String CHECKSUM_SUFFIX = ".sha256";
    private static final String SIGNATURE_SUFFIX = ".asc";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private ReleaseParser() {
    }

    static Optional<AvailableUpdate> newerThan(JsonNode release, ReleaseVersion current, Installation installation, String repository) {
        var version = ReleaseVersion.parse(release.path("tag_name").asString(""));
        if (version.isEmpty() || version.get().compareTo(current) <= 0) {
            return Optional.empty();
        }
        var page = pageOf(release, repository);
        var name = assetName(installation.kind(), version.get().toString(), installation.arch());
        var prefix = "https://github.com/" + repository + "/releases/download/";
        var installer = asset(release, name, prefix);
        var checksum = installer == null ? null : asset(release, name + CHECKSUM_SUFFIX, prefix);
        if (installer == null || checksum == null) {
            return Optional.empty();
        }
        if (asset(release, name + SIGNATURE_SUFFIX, prefix) == null) {
            return Optional.of(AvailableUpdate.releasePageOnly(version.get().toString(), page));
        }
        return Optional.of(new AvailableUpdate(
                version.get().toString(),
                name,
                installer.path("size").asLong(0),
                URI.create(installer.path("browser_download_url").asString()),
                URI.create(checksum.path("browser_download_url").asString()),
                page));
    }

    /** For an installation that cannot be classified: no asset is needed, only the tag and the release page. */
    static Optional<AvailableUpdate> pageOnlyNewerThan(JsonNode release, ReleaseVersion current, String repository) {
        var version = ReleaseVersion.parse(release.path("tag_name").asString(""));
        if (version.isEmpty() || version.get().compareTo(current) <= 0) {
            return Optional.empty();
        }
        return Optional.of(AvailableUpdate.releasePageOnly(version.get().toString(), pageOf(release, repository)));
    }

    /** The release's {@code html_url} when it is a page of the configured repository, else the repository's releases list. */
    private static URI pageOf(JsonNode release, String repository) {
        var releases = "https://github.com/" + repository + "/";
        var page = release.path("html_url").asString("");
        var rest = page.startsWith(releases) ? page.substring(releases.length()) : "";
        var plain = rest.matches("[A-Za-z0-9._~/-]+") && Arrays.stream(rest.split("/")).noneMatch(s -> s.equals(".") || s.equals(".."));
        return URI.create(plain ? page : releases + "releases");
    }

    static String assetName(InstallKind kind, String version, String arch) {
        return switch (kind) {
            case MSI -> "Astrolabe-Studio-" + version + ".msi";
            case DMG -> "Astrolabe-Studio-" + version + "-" + arch + ".dmg";
            case TAR_GZ -> linux(version, arch, "tar.gz");
            case DEB -> linux(version, arch, "deb");
            case RPM -> linux(version, arch, "rpm");
        };
    }

    private static String linux(String version, String arch, String extension) {
        return "astrolabe-studio-" + version + "-linux-" + arch + "." + extension;
    }

    private static boolean isTag(String tag) {
        return SAFE_NAME.matcher(tag).matches() && !tag.startsWith(".");
    }

    /** The asset of exactly this name whose download URL is a plain release download of the configured repository. */
    private static JsonNode asset(JsonNode release, String name, String urlPrefix) {
        for (var asset : release.path("assets")) {
            var url = asset.path("browser_download_url").asString("");
            if (asset.path("name").asString("").equals(name) && SAFE_NAME.matcher(name).matches() && url.startsWith(urlPrefix) && url.endsWith("/" + name) && url.length() > urlPrefix.length() + name.length() + 1
                    && isTag(url.substring(urlPrefix.length(), url.length() - name.length() - 1))) {
                return asset;
            }
        }
        return null;
    }
}
