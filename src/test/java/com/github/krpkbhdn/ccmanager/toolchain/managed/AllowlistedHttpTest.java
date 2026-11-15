package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AllowlistedHttpTest {

    @Test
    void followsAllowedRedirectsToTheFinalBody() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/a", "/b");
            server.body("/b", "done");

            try (var body = server.http().open(server.uri("/a")).body()) {
                assertThat(new String(body.readAllBytes())).isEqualTo("done");
            }
        }
    }

    @Test
    void signedCloudFrontLocationKeepsItsQueryStringIntact() throws Exception {
        var location = "https://download-cdn.jetbrains.com/language-server/kotlin-server/271.2085.0/kotlin-server-271.2085.0.win.zip"
                + "?Key-Pair-Id=K1ABC&Expires=1800000000&Signature=ab-cd~ef_gh%3D%3D";
        try (var server = new LocalHttp()) {
            server.redirect("/a", location);
            var http = new AllowlistedHttp(Set.of("127.0.0.1", "download-cdn.jetbrains.com"), Duration.ofSeconds(5), true);

            assertThat(http.firstRedirect(server.uri("/a"))).hasToString(location);
        }
    }

    @Test
    void redirectToUnlistedHostIsRefused()throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/a", "https://evil.example/x");

            assertThatThrownBy(() -> server.http().open(server.uri("/a")))
                    .isInstanceOf(IOException.class).hasMessageContaining("evil.example");
        }
    }

    @Test
    void redirectToPlainHttpIsRefused() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/a", "http://download.example/x");

            assertThatThrownBy(() -> server.http().open(server.uri("/a")))
                    .isInstanceOf(IOException.class).hasMessageContaining("https");
        }
    }

    @Test
    void redirectWithUserInfoOrOtherPortIsRefused() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/user", "https://user:pw@download.example/x");
            server.redirect("/port", "https://download.example:8443/x");

            assertThatThrownBy(() -> server.http().open(server.uri("/user"))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> server.http().open(server.uri("/port"))).isInstanceOf(IOException.class);
        }
    }

    @Test
    void tooManyRedirectsFail() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/loop", "/loop");

            assertThatThrownBy(() -> server.http().open(server.uri("/loop")))
                    .isInstanceOf(IOException.class).hasMessageContaining("redirects");
        }
    }

    @Test
    void exactlyTheMaximumNumberOfRedirectsIsAllowed() throws Exception {
        try (var server = new LocalHttp()) {
            for (int i = 0; i < AllowlistedHttp.MAX_REDIRECTS; i++) {
                server.redirect("/h" + i, "/h" + (i + 1));
            }
            server.body("/h" + AllowlistedHttp.MAX_REDIRECTS, "end");

            try (var body = server.http().open(server.uri("/h0")).body()) {
                assertThat(new String(body.readAllBytes())).isEqualTo("end");
            }
        }
    }

    @Test
    void errorStatusFails() throws Exception {
        try (var server = new LocalHttp()) {
            server.route("/missing", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });

            assertThatThrownBy(() -> server.http().open(server.uri("/missing")))
                    .isInstanceOf(IOException.class).hasMessageContaining("404");
        }
    }

    @Test
    void plainHttpToLoopbackNeedsTheTestSwitch() {
        var strict = new AllowlistedHttp(Set.of("127.0.0.1"), Duration.ofSeconds(1));

        assertThatThrownBy(() -> strict.open(URI.create("http://127.0.0.1:1/x"))).isInstanceOf(IOException.class);
    }
}
