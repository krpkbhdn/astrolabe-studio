package com.github.krpkbhdn.astrolabe.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The temporary files of browser captures in flight under the review root, and the sweep of leftovers. */
final class ReviewCaptureFiles {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewCaptureFiles.class);

    private final Path root;
    private final ReviewProperties properties;

    ReviewCaptureFiles(Path root, ReviewProperties properties) {
        this.root = root;
        this.properties = properties;
    }

    <T> T withTempCapture(byte[] image, Function<Path, T> action) {
        if (image.length > properties.maxImageBytes()) {
            throw new IllegalArgumentException("The screenshot is larger than " + properties.maxImageBytes() + " bytes.");
        }
        Path file = null;
        try {
            var captures = Files.createDirectories(root.resolve(ReviewService.CAPTURE_FOLDER));
            file = Files.createTempFile(captures, "capture-", ".img");
            Files.write(file, image);
            return action.apply(file);
        } catch (IOException e) {
            LOG.warn("Could not store a screenshot: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("The screenshot could not be stored.");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    LOG.warn("Could not delete a temporary capture file.");
                }
            }
        }
    }

    void deleteStaleCaptures(Instant cutoff) {
        var captures = root.resolve(ReviewService.CAPTURE_FOLDER);
        if (!Files.isDirectory(captures)) {
            return;
        }
        try (var files = Files.list(captures)) {
            for (var file : files.toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not sweep stale capture files: {}", e.getClass().getSimpleName());
        }
    }
}
