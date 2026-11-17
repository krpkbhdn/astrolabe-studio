package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Streams an archive into a directory while hashing it, within a size and time limit; a failed download leaves no file. */
public final class ArchiveDownload {

    /** Percent is 0-100, or -1 when the server did not say how large the file is. */
    @FunctionalInterface
    public interface Progress {
        void update(int percent, long bytesRead, long totalBytes);
    }

    public record Downloaded(Path file, String sha256) {}

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int PERCENT = 100;

    private final AllowlistedHttp http;
    private final Duration timeout;
    private final long maxBytes;

    public ArchiveDownload(AllowlistedHttp http, Duration timeout, long maxBytes) {
        this.http = http;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
    }

    public Downloaded download(URI uri, Path directory, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "download-", ".part");
        try {
            return new Downloaded(file, copy(uri, file, progress));
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(file);
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw e;
        }
    }

    private String copy(URI uri, Path file, Progress progress) throws IOException, InterruptedException {
        MessageDigest digest = newDigest();
        HttpResponse<InputStream> response = http.open(uri);
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (total > maxBytes) {
            response.body().close();
            throw tooLarge();
        }
        long read = 0;
        int lastPercent = -2;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = response.body(); var deadline = new ReadDeadline(in, timeout, "Download");
             OutputStream out = Files.newOutputStream(file)) {
            int n;
            while ((n = readChunk(in, buffer, deadline)) != -1) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Download cancelled");
                }
                read += n;
                if (read > maxBytes) {
                    throw tooLarge();
                }
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
                int percent = total > 0 ? (int) (read * PERCENT / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.update(percent, read, total);
                }
            }
            deadline.rethrowIfExpired(new IOException("Stream closed at the deadline"));
        }
        if (total >= 0 && read != total) {
            throw new IOException("Download ended early: " + read + " of " + total + " bytes");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int readChunk(InputStream in, byte[] buffer, ReadDeadline deadline) throws IOException {
        try {
            return in.read(buffer);
        } catch (IOException e) {
            deadline.rethrowIfExpired(e);
            throw e;
        }
    }

    private IOException tooLarge() {
        return new IOException("Download is larger than the allowed " + maxBytes + " bytes");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
