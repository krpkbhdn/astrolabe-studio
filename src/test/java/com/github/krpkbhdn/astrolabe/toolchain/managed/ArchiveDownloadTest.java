package com.github.krpkbhdn.astrolabe.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveDownloadTest {

    private static final byte[] DATA = new byte[300_000];

    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = (byte) (i * 31);
        }
    }

    @TempDir
    Path directory;

    private void serveData(LocalHttp server) {
        server.route("/a.zip", exchange -> {
            exchange.sendResponseHeaders(200, DATA.length);
            exchange.getResponseBody().write(DATA);
            exchange.close();
        });
    }

    private ArchiveDownload download(LocalHttp server, long maxBytes) {
        return new ArchiveDownload(server.http(), Duration.ofSeconds(30), maxBytes);
    }

    private long leftoverFiles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    @Test
    void savesTheFileWithItsSha256AndReportsProgress() throws Exception {
        try (var server = new LocalHttp()) {
            serveData(server);
            List<Integer> percents = new ArrayList<>();

            var result = download(server, 1_000_000).download(server.uri("/a.zip"), directory, (p, read, total) -> percents.add(p));

            assertThat(Files.readAllBytes(result.file())).isEqualTo(DATA);
            assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DATA)));
            assertThat(percents).isNotEmpty().endsWith(100).isSorted();
        }
    }

    @Test
    void oversizeDownloadFailsAndLeavesNoFile() throws Exception {
        try (var server = new LocalHttp()) {
            serveData(server);

            assertThatThrownBy(() -> download(server, 1000).download(server.uri("/a.zip"), directory, (p, r, t) -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("larger");
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void oversizeWithoutContentLengthIsStoppedWhileReading() throws Exception {
        try (var server = new LocalHttp()) {
            server.route("/chunked", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try {
                    exchange.getResponseBody().write(DATA);
                } catch (IOException ignored) {
                    // the client hung up at the limit
                }
                exchange.close();
            });

            assertThatThrownBy(() -> download(server, 1000).download(server.uri("/chunked"), directory, (p, r, t) -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("larger");
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void truncatedDownloadFailsAndLeavesNoFile() throws Exception {
        try (var server = new LocalHttp()) {
            server.route("/cut", exchange -> {
                exchange.sendResponseHeaders(200, DATA.length);
                exchange.getResponseBody().write(DATA, 0, 1000);
                exchange.getResponseBody().flush();
                exchange.close();
            });

            assertThatThrownBy(() -> download(server, 1_000_000).download(server.uri("/cut"), directory, (p, r, t) -> {}))
                    .isInstanceOf(IOException.class);
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void stalledServerTimesOutDuringTheReadAndLeavesNoFile() throws Exception {
        try (var server = new LocalHttp()) {
            server.stall("/stuck");
            var downloader = new ArchiveDownload(server.http(), Duration.ofMillis(300), 1_000_000);

            assertThatThrownBy(() -> downloader.download(server.uri("/stuck"), directory, (p, r, t) -> {}))
                    .isInstanceOf(IOException.class).hasMessageContaining("timed out");
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void interruptCancelsAndLeavesNoFile() throws Exception {
        try (var server = new LocalHttp()) {
            serveData(server);
            Thread.currentThread().interrupt();

            try {
                assertThatThrownBy(() -> download(server, 1_000_000).download(server.uri("/a.zip"), directory, (p, r, t) -> {}))
                        .isInstanceOf(InterruptedException.class);
            } finally {
                Thread.interrupted();
            }
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void interruptDuringTheCopyCancels() throws Exception {
        try (var server = new LocalHttp()) {
            serveData(server);

            assertThatThrownBy(() -> download(server, 1_000_000).download(server.uri("/a.zip"), directory, (p, r, t) -> Thread.currentThread().interrupt()))
                    .isInstanceOf(InterruptedException.class);
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void interruptDuringAStalledReadCancelsAtOnce() throws Exception {
        try (var server = new LocalHttp(); var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var served = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            server.route("/stuck", exchange -> {
                exchange.sendResponseHeaders(200, 1_000_000);
                exchange.getResponseBody().flush();
                served.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            var downloader = new ArchiveDownload(server.http(), Duration.ofMinutes(5), 1_000_000);
            var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var running = pool.submit(() -> {
                thread.set(Thread.currentThread());
                downloader.download(server.uri("/stuck"), directory, (p, r, t) -> {});
                return null;
            });
            assertThat(served.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            thread.get().interrupt();

            assertThatThrownBy(() -> running.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InterruptedException.class);
            release.countDown();
            assertThat(leftoverFiles()).isZero();
        }
    }

    @Test
    void refusedRedirectLeavesNoFile() throws Exception {
        try (var server = new LocalHttp()) {
            server.redirect("/a.zip", "https://evil.example/a.zip");

            assertThatThrownBy(() -> download(server, 1_000_000).download(server.uri("/a.zip"), directory, (p, r, t) -> {}))
                    .isInstanceOf(IOException.class);
            assertThat(leftoverFiles()).isZero();
        }
    }
}
