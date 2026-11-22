package com.github.krpkbhdn.astrolabe.update;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** File helpers shared by the update classes: a SHA-256 of a file and tree deletion that never follows symlinks. */
final class UpdateFiles {

    private static final int HASH_BUFFER = 64 * 1024;

    private UpdateFiles() {
    }

    /** @return the lower-case hex SHA-256 of the file's current bytes */
    static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[HASH_BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IOException when the file's bytes do not hash to {@code expectedHex} (case-insensitive) */
    static void requireSha256(Path file, String expectedHex, String what) throws IOException {
        if (!sha256(file).equalsIgnoreCase(expectedHex)) {
            throw new IOException(what + " differs from the verified download");
        }
    }

    /** Deletes a file or folder tree; a symlink is removed itself, never followed; a missing path is fine. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Like {@link #deleteTree} for leftovers the OS cleans eventually: a null path or a failure is ignored. */
    static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // the temp directory is cleaned by the OS eventually
        }
    }
}
