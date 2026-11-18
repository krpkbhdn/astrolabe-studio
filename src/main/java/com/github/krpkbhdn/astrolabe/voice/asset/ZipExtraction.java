package com.github.krpkbhdn.astrolabe.voice.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipFile;
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter;

/** Unpacks a zip beside its target as {@code .part} and moves it into place only when every entry stayed inside it. */
final class ZipExtraction {

    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_PAUSE_MILLIS = 300;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    /** Generous caps over the known runtimes (a few thousand files); they only stop a zip bomb. */
    static final int MAX_ENTRIES = 20_000;
    /** The Windows CUDA runtime unpacks to about 1.7 times its archive, so this leaves wide room. */
    private static final long MAX_EXPANSION_FACTOR = 10;

    private ZipExtraction() {}

    static long unpackedLimit(long archiveBytes) {
        return archiveBytes * MAX_EXPANSION_FACTOR;
    }

    /** Fills the staging directory it is handed; whatever it throws removes the staging directory again. */
    interface Stager {
        void stage(Path partial) throws IOException;
    }

    /** Stages into {@code <directory>.part}, then replaces {@code directory}; a failure leaves it untouched and removes the partial one. */
    static void stageAndReplace(Path directory, Stager stager) throws IOException {
        var partial = directory.resolveSibling(directory.getFileName() + ".part");
        deleteRecursively(partial);
        try {
            Files.createDirectories(partial);
            stager.stage(partial);
            replace(directory, partial);
        } finally {
            deleteRecursively(partial);
        }
    }

    static void extract(Path zip, Path directory, int maxEntries, long maxBytes) throws IOException {
        try (var archive = new ZipFile(zip.toFile())) {
            if (archive.size() > maxEntries) {
                throw new IOException("Archive has too many entries: " + archive.size());
            }
            stageAndReplace(directory, partial -> {
                var root = partial.toAbsolutePath().normalize();
                var remaining = maxBytes;
                for (var entry : archive.stream().toList()) {
                    var target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) {
                        throw new IOException("Archive entry escapes the target directory: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (var in = archive.getInputStream(entry); var out = Files.newOutputStream(target)) {
                            remaining -= copyAtMost(in, out, remaining);
                        }
                    }
                }
            });
        }
    }

    /**
     * Unpacks only {@code entryName} (a file) as its plain file name into {@code directory}; every other entry,
     * links included, is never read or created.
     */
    static void extractEntry(Path zip, Path directory, String entryName, long maxBytes) throws IOException {
        try (var archive = new ZipFile(zip.toFile())) {
            var entry = archive.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Archive has no file " + entryName);
            }
            stageAndReplace(directory, partial -> {
                var target = partial.resolve(entryName.substring(entryName.lastIndexOf('/') + 1));
                try (var in = archive.getInputStream(entry); var out = Files.newOutputStream(target)) {
                    copyAtMost(in, out, maxBytes);
                }
            });
        }
    }

    /** Counts the bytes actually inflated, since an entry's declared size can lie. */
    static long copyAtMost(InputStream in, OutputStream out, long limit) throws IOException {
        var buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                throw new IOException("Archive is larger than allowed when unpacked");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    static void replace(Path directory, Path partial) throws IOException {
        var previous = directory.resolveSibling(directory.getFileName() + ".old");
        deleteRecursively(previous);
        var hadPrevious = Files.exists(directory);
        if (hadPrevious) {
            moveIntoPlace(directory, previous);
        }
        try {
            moveIntoPlace(partial, directory);
        } catch (IOException e) {
            if (hadPrevious) {
                moveIntoPlace(previous, directory);
            }
            throw e;
        }
        deleteRecursively(previous);
    }

    /** Retries because on Windows a virus scanner or indexer may briefly hold the freshly written files. */
    static void moveIntoPlace(Path from, Path to) throws IOException {
        moveIntoPlace(from, to, MOVE_RETRY_PAUSE_MILLIS);
    }

    static void moveIntoPlace(Path from, Path to, long pauseMillis) throws IOException {
        for (var attempt = 1; ; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                if (attempt == MOVE_ATTEMPTS) {
                    throw new IOException("Could not move " + from.getFileName() + " into place after " + MOVE_ATTEMPTS
                            + " attempts; another program may be using its files", e);
                }
                pause(pauseMillis);
            }
        }
    }

    private static void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            TreeDeleter.delete(path);
        }
    }
}
