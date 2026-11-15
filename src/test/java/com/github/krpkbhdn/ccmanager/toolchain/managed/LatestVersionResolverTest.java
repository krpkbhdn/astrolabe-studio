package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LatestVersionResolverTest {

    private static final Pattern FILE_NAME = Pattern.compile("tool-([0-9][0-9.]*-[0-9]{12})\\.tar\\.gz");
    private static final Pattern TAG = Pattern.compile("/tag/tool/v([0-9]+(\\.[0-9]+)+)$");

    @Test
    void readsTheVersionFromAFileBodyWithCrLf() throws Exception {
        try (var server = new LocalHttp()) {
            server.body("/latest.txt", "tool-1.64.0-202611121920.tar.gz\r\n");

            assertThat(new LatestVersionResolver(server.http(), Duration.ofSeconds(5)).resolve(server.uri("/latest.txt"), FILE_NAME, false))
                    .isEqualTo("1.64.0-202611121920");
        }
    }

    @Test
    void readsTheVersionFromTheRedirectLocation() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/latest", "https://download.example/o/r/releases/tag/tool/v271.2085.0");

            assertThat(new LatestVersionResolver(server.http(), Duration.ofSeconds(5)).resolve(server.uri("/latest"), TAG, true))
                    .isEqualTo("271.2085.0");
        }
    }

    @Test
    void redirectModeFailsWhenThereIsNoRedirect() throws Exception {
        try (var server = new LocalHttp()) {
            server.body("/latest", "tool");

            assertThatThrownBy(() -> new LatestVersionResolver(server.http(), Duration.ofSeconds(5)).resolve(server.uri("/latest"), TAG, true))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void bodyWithoutAMatchFails() throws Exception {
        try (var server = new LocalHttp()) {
            server.body("/latest.txt", "<html>oops</html>");

            assertThatThrownBy(() -> new LatestVersionResolver(server.http(), Duration.ofSeconds(5)).resolve(server.uri("/latest.txt"), FILE_NAME, false))
                    .isInstanceOf(IOException.class).hasMessageContaining("No version");
        }
    }

    @Test
    void stalledBodyTimesOut() throws Exception {
        try (var server = new LocalHttp()) {
            server.stall("/latest.txt");

            assertThatThrownBy(() -> new LatestVersionResolver(server.http(), Duration.ofMillis(300))
                    .resolve(server.uri("/latest.txt"), FILE_NAME, false))
                    .isInstanceOf(IOException.class).hasMessageContaining("timed out");
        }
    }

    @Test
    void oversizeBodyIsRefused() throws Exception {
        try (var server = new LocalHttp()) {
            server.body("/latest.txt", "x".repeat(70_000));

            assertThatThrownBy(() -> new LatestVersionResolver(server.http(), Duration.ofSeconds(5))
                    .resolve(server.uri("/latest.txt"), FILE_NAME, false))
                    .isInstanceOf(IOException.class).hasMessageContaining("too large");
        }
    }

    @Test
    void unsafeVersionTextIsRefusedBeforeItReachesAUrl() throws Exception {
        try (var server = new LocalHttp()) {
            server.body("/latest.txt", "v=../../evil");

            assertThatThrownBy(() -> new LatestVersionResolver(server.http(), Duration.ofSeconds(5))
                    .resolve(server.uri("/latest.txt"), Pattern.compile("v=(.*)"), false))
                    .isInstanceOf(IOException.class).hasMessageContaining("Unusable");
        }
    }
}
