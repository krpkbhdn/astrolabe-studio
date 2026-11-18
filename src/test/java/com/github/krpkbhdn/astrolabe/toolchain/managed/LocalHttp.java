package com.github.krpkbhdn.astrolabe.toolchain.managed;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A loopback http server for the download tests, plus an AllowlistedHttp that may talk to it. */
final class LocalHttp implements AutoCloseable {

    private final HttpServer server;

    private final CountDownLatch released = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    LocalHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    /** Sends the headers of a long body, then nothing until the server closes. */
    void stall(String path) {
        route(path, exchange -> {
            exchange.sendResponseHeaders(200, 1_000_000);
            exchange.getResponseBody().flush();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
    }

    void route(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    void body(String path, String text) {
        route(path, exchange -> {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    void redirect(String path, String location) {
        route(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    AllowlistedHttp http() {
        return new AllowlistedHttp(Set.of("127.0.0.1", "download.example"), Duration.ofSeconds(5), true);
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
