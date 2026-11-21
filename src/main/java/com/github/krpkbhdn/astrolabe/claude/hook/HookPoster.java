package com.github.krpkbhdn.astrolabe.claude.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Command-hook transport: posts its stdin to a loopback hook URL with the bearer token from the environment, so the token never
 * appears in a command line. java.base only, so a bare {@code java -cp} can run it. Exit 0 whenever the call was attempted (a gone server or an error
 * answer must not break Claude, it only leaves one stderr line); exit 1 with one stderr line for a missing token or an unusable URL; stdout stays empty.
 */
public final class HookPoster {

    public static final String FLAG = "--post-hook";
    public static final String TOKEN_FILE_FLAG = "--token-file";
    static final int FAILURE_EXIT = 1;
    private static final String IPV4_LOOPBACK = "127.0.0.1";
    private static final String LOCALHOST = "localhost";
    private static final String IPV6_LOOPBACK = "[::1]";
    private static final Set<String> LOOPBACK_HOSTS = Set.of(IPV4_LOOPBACK, LOCALHOST, IPV6_LOOPBACK);
    /** Constants, not properties: this runs before Spring starts. Connect plus read stay inside the 5 s SessionStart hook timeout. */
    static final int CONNECT_TIMEOUT_MILLIS = 1000;
    static final int READ_TIMEOUT_MILLIS = 2000;

    private HookPoster() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** {@code args} is {@code --post-hook <url> [--token-file <path>]}; reads stdin, the token file, else the token env variable. Returns the process exit code. */
    public static int run(String[] args) {
        return run(args, System.getenv(HookServer.SESSION_TOKEN_ENV), System.in, System.err);
    }

    static int run(String[] args, String envToken, InputStream stdin, PrintStream err) {
        return run(args, envToken, stdin, err, READ_TIMEOUT_MILLIS);
    }

    static int run(String[] args, String envToken, InputStream stdin, PrintStream err, int readTimeoutMillis) {
        var token = tokenFromFile(args);
        if (token.isEmpty()) {
            token = envToken == null ? "" : envToken;
        }
        if (token.isEmpty()) {
            return fail(err, "no token (" + TOKEN_FILE_FLAG + " unreadable or empty, " + HookServer.SESSION_TOKEN_ENV + " is not set)");
        }
        var url = args.length > 1 ? args[1] : "";
        if (!isLoopbackHttp(url)) {
            return fail(err, "the hook URL must be http on a loopback host");
        }
        try {
            var status = post(url, stdin.readAllBytes(), token, readTimeoutMillis);
            if (status / 100 != 2) {
                err.println("astrolabe hook: the server answered HTTP " + status);
            }
        } catch (IOException | RuntimeException e) {
            err.println("astrolabe hook: the server could not be reached (" + e.getClass().getSimpleName() + ")");
        }
        return 0;
    }

    /** Empty when the argument is absent or the file cannot be read, so the caller falls back to the environment. */
    private static String tokenFromFile(String[] args) {
        if (args.length < 4 || !TOKEN_FILE_FLAG.equals(args[2])) {
            return "";
        }
        try {
            return Files.readString(Path.of(args[3])).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static int fail(PrintStream err, String message) {
        err.println("astrolabe hook: " + message);
        return FAILURE_EXIT;
    }

    private static boolean isLoopbackHttp(String url) {
        try {
            var uri = URI.create(url);
            return "http".equals(uri.getScheme()) && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int post(String url, byte[] body, String token, int readTimeoutMillis) throws IOException {
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setDoOutput(true);
            try (var out = connection.getOutputStream()) {
                out.write(body);
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
