package com.github.krpkbhdn.ccmanager.toolchain.managed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** GET over https only to allowlisted hosts; redirects are followed by hand and every hop is checked again. */
public final class AllowlistedHttp {

    static final int MAX_REDIRECTS = 5;
    private static final String LOOPBACK = "127.0.0.1";
    private static final int OK = 200;
    private static final int HTTPS_PORT = 443;

    private final HttpClient client;
    private final Set<String> hosts;
    private final Duration requestTimeout;
    private final boolean allowLoopbackHttp;

    public AllowlistedHttp(Set<String> hosts, Duration connectTimeout) {
        this(hosts, connectTimeout, false);
    }

    /** Tests set allowLoopbackHttp to talk to a local server; that host is then allowed over http on any port. */
    AllowlistedHttp(Set<String> hosts, Duration connectTimeout, boolean allowLoopbackHttp) {
        this.hosts = Set.copyOf(hosts);
        this.requestTimeout = connectTimeout;
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** Opens the final 200 response after following redirects; the caller closes the body. */
    public HttpResponse<InputStream> open(URI uri) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = send(current);
            if (response.statusCode() == OK) {
                return response;
            }
            if (!isRedirect(response)) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " from " + current.getHost());
            }
            current = redirectTarget(current, response);
        }
        throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + uri.getHost());
    }

    /** A small text body read within {@code timeout} and {@code maxBytes}; a stalled or oversize body is an IOException. */
    public String readText(URI uri, int maxBytes, Duration timeout) throws IOException, InterruptedException {
        try (InputStream body = open(uri).body(); var deadline = new ReadDeadline(body, timeout, "Reading from " + uri.getHost())) {
            byte[] bytes;
            try {
                bytes = body.readNBytes(maxBytes + 1);
            } catch (IOException e) {
                deadline.rethrowIfExpired(e);
                throw e;
            }
            deadline.rethrowIfExpired(new IOException("Body ended"));
            if (bytes.length > maxBytes) {
                throw new IOException("Response from " + uri.getHost() + " is too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** The checked Location of the first answer, which must be a redirect; nothing is fetched from it. */
    public URI firstRedirect(URI uri) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(uri);
        if (!isRedirect(response)) {
            response.body().close();
            throw new IOException("Expected a redirect from " + uri.getHost() + ", got HTTP " + response.statusCode());
        }
        return redirectTarget(uri, response);
    }

    private URI redirectTarget(URI from, HttpResponse<InputStream> response) throws IOException {
        response.body().close();
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException("Redirect without Location from " + from.getHost()));
        URI target = from.resolve(location);
        check(target);
        return target;
    }

    private HttpResponse<InputStream> send(URI uri) throws IOException, InterruptedException {
        check(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean isRedirect(HttpResponse<?> response) {
        return switch (response.statusCode()) {
            case 301, 302, 303, 307, 308 -> true;
            default -> false;
        };
    }

    private void check(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IOException("Host not allowed: " + host);
        }
        if (allowLoopbackHttp && LOOPBACK.equals(host) && "http".equals(uri.getScheme())) {
            return;
        }
        if (!"https".equals(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT)) {
            throw new IOException("Only https on the default port is allowed: " + host);
        }
    }
}
