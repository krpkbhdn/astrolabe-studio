package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the newest version of a tool from a text file body or from the redirect target of a "latest" URL. */
public final class LatestVersionResolver {

    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.-]{0,63}");
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final AllowlistedHttp http;
    private final Duration timeout;

    public LatestVersionResolver(AllowlistedHttp http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    /** The first group of the pattern, found in the body (redirect false) or in the Location header (redirect true). */
    public String resolve(URI url, Pattern pattern, boolean redirect) throws IOException, InterruptedException {
        String text = redirect ? http.firstRedirect(url).toString() : http.readText(url, MAX_BODY_BYTES, timeout);
        Matcher matcher = pattern.matcher(text.strip());
        if (!matcher.find() || matcher.groupCount() < 1) {
            throw new IOException("No version found at " + url.getHost());
        }
        String version = matcher.group(1);
        if (version == null || !SAFE_VERSION.matcher(version).matches()) {
            throw new IOException("Unusable version string from " + url.getHost());
        }
        return version;
    }

}
