package com.github.krpkbhdn.astrolabe.review;

import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Visual reviews: screenshots with marks, stored in SQLite and under a temp folder per review. */
@Service
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewService {

    private static final Path DEFAULT_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "astrolabe", "reviews");
    /** The longest title a browser capture gets; the Web tab builds its titles to this limit. */
    public static final int MAX_CAPTURE_TITLE = 120;
    /** Folder under the root that holds the temporary files of captures in flight; the cleanup sweeps stale ones. */
    public static final String CAPTURE_FOLDER = "captures";
    /** The longest note a browser capture gets. */
    public static final int MAX_CAPTURE_NOTE = 1000;
    private static final String TOO_MANY_FOR_CLAUDE = "too many reviews are waiting in this project: ask the user to delete some in the Review tab";
    private static final String TOO_MANY_FOR_USER = "Too many reviews are waiting in this project: delete some in the Review tab first.";

    private final ReviewProperties properties;
    private final ReviewRepository repository;
    private final ReviewCaptureFiles captureFiles;
    private final ReviewImageStore imageStore;
    private final ReviewFeedbackWriter feedbackWriter;
    private final TransactionTemplate transaction;
    private final Path root;
    private final ChangeListeners listeners = new ChangeListeners("Review");
    /** Serialises appending a capture against sending, approving and deleting, so no image lands in a settled review. */
    private final ReentrantLock captureLock = new ReentrantLock();

    @Autowired
    public ReviewService(JdbcClient jdbc, ReviewImageStore imageStore, ReviewFeedbackWriter feedbackWriter, TransactionTemplate transaction,
                         ReviewProperties properties) {
        this(jdbc, imageStore, feedbackWriter, transaction, properties, DEFAULT_ROOT);
    }

    public ReviewService(JdbcClient jdbc, ReviewImageStore imageStore, ReviewFeedbackWriter feedbackWriter, TransactionTemplate transaction,
                         ReviewProperties properties, Path root) {
        this.properties = properties;
        this.repository = new ReviewRepository(jdbc);
        this.captureFiles = new ReviewCaptureFiles(root, properties);
        this.imageStore = imageStore;
        this.feedbackWriter = feedbackWriter;
        this.transaction = transaction;
        this.root = root;
    }

    /** @throws IllegalArgumentException for a blank title or images the store rejects */
    public Review submit(long projectId, String astrolabeSessionId, String title, String note, List<SubmittedImage> images) {
        return submit(projectId, astrolabeSessionId, title, note, images, TOO_MANY_FOR_CLAUDE);
    }

    /** Hands a browser screenshot (PNG/JPEG bytes) to the Review tab; every image check of the store still applies. The page title is untrusted. */
    public Review submitCapture(long projectId, String title, String note, byte[] image, String label) {
        return captureFiles.withTempCapture(image, file -> submit(projectId, null, capped(title, MAX_CAPTURE_TITLE), capped(note, MAX_CAPTURE_NOTE),
                List.of(captureImage(file, label)), TOO_MANY_FOR_USER));
    }

    /** Appends a browser screenshot to a PENDING review of the project; the review's image limit and the store's image checks apply. */
    public Review addCapture(long reviewId, long projectId, byte[] image, String label) {
        captureLock.lock();
        try {
            captureFiles.withTempCapture(image, file -> {
                var lastPosition = transaction.execute(status -> openLastPosition(reviewId, projectId));
                var stored = imageStore.store(directoryOf(reviewId), List.of(captureImage(file, label)), lastPosition + 2).get(0);
                try {
                    transaction.executeWithoutResult(status -> repository.insertImage(reviewId, openLastPosition(reviewId, projectId) + 1, stored));
                } catch (RuntimeException e) {
                    try {
                        Files.deleteIfExists(directoryOf(reviewId).resolve(stored.fileName()));
                    } catch (IOException | RuntimeException cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                    throw e;
                }
                return null;
            });
        } finally {
            captureLock.unlock();
        }
        listeners.fire();
        return find(reviewId).orElseThrow();
    }

    /** The PENDING reviews of the project, newest first, with how many screenshots each holds. */
    public List<CaptureTarget> captureTargets(long projectId) {
        return repository.captureTargets(projectId, properties.maxImages());
    }

    public record CaptureTarget(long id, String title, Instant createdAt, int images, int maxImages) {
        public boolean full() {
            return images >= maxImages;
        }
    }

    public enum Reason { FULL, SETTLED, MISSING }

    public static class CaptureRefusedException extends IllegalStateException {
        private final Reason reason;

        CaptureRefusedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** @return the highest image position of the review, -1 when it has none; refuses a missing, settled or full review */
    private int openLastPosition(long reviewId, long projectId) {
        var review = find(reviewId);
        if (review.isEmpty() || review.get().projectId() != projectId) {
            throw new CaptureRefusedException(Reason.MISSING, "This review no longer exists.");
        }
        if (review.get().status() != ReviewStatus.PENDING) {
            throw new CaptureRefusedException(Reason.SETTLED, "This review was already sent or approved: start a new one.");
        }
        var row = repository.imageRow(reviewId);
        if (row.count() >= properties.maxImages()) {
            throw new CaptureRefusedException(Reason.FULL, "This review already holds " + properties.maxImages() + " screenshots, the most it can take.");
        }
        return row.lastPosition();
    }

    private static SubmittedImage captureImage(Path file, String label) {
        var safeLabel = capped(label, MAX_CAPTURE_TITLE);
        return new SubmittedImage(file, safeLabel.isEmpty() ? "screenshot" : safeLabel);
    }

    /** Removes capture files older than {@code cutoff}: leftovers of a crash between writing a capture and deleting it. */
    public void deleteStaleCaptures(Instant cutoff) {
        captureFiles.deleteStaleCaptures(cutoff);
    }

    /** What the browser-side capture planner may send: the same caps the store enforces. */
    public CaptureLimits limits() {
        return new CaptureLimits(properties.maxImageSide(), properties.maxImagePixels(), properties.maxImageBytes());
    }

    public record CaptureLimits(int maxSide, long maxPixels, long maxBytes) {
    }

    private static String capped(String text, int max) {
        var line = PromptText.oneLine(text == null ? "" : text);
        return line.length() <= max ? line : line.substring(0, max).strip();
    }

    private Review submit(long projectId, String astrolabeSessionId, String title, String note, List<SubmittedImage> images, String tooManyMessage) {
        var oneLineTitle = title == null ? "" : PromptText.oneLine(title);
        if (oneLineTitle.isEmpty()) {
            throw new IllegalArgumentException("The review needs a title.");
        }
        long id;
        captureLock.lock();
        try {
            id = transaction.execute(status -> {
                if (repository.countForProject(projectId) >= properties.maxReviewsPerProject()) {
                    throw new IllegalArgumentException(tooManyMessage);
                }
                return repository.insertReview(projectId, astrolabeSessionId, oneLineTitle, note == null ? "" : note);
            });
            try {
                repository.insertImages(id, imageStore.store(directoryOf(id), images));
            } catch (RuntimeException e) {
                try {
                    removeQuietly(id);
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        } finally {
            captureLock.unlock();
        }
        listeners.fire();
        return find(id).orElseThrow();
    }

    public List<Review> list(long projectId) {
        return repository.list(projectId);
    }

    public Optional<Review> find(long id) {
        return repository.find(id);
    }

    public List<ReviewImage> images(long reviewId) {
        return repository.images(reviewId);
    }

    public Path imagePath(ReviewImage image) {
        return directoryOf(image.reviewId()).resolve(image.fileName());
    }

    public Path directoryOf(long reviewId) {
        return root.resolve(Long.toString(reviewId));
    }

    public Path rootDirectory() {
        return root;
    }

    public List<ReviewMark> marks(long imageId) {
        return repository.marks(imageId);
    }

    /** Replaces all marks of the image; coordinates are clamped to the 0..1 image box. */
    public void saveMarks(long imageId, List<MarkDraft> marks) {
        var normalised = marks.stream().map(ReviewMarkCodec::normalised).toList();
        transaction.executeWithoutResult(status -> repository.replaceMarks(imageId, normalised));
        listeners.fire();
    }

    /** Writes the feedback files, marks the review as sent and returns the prompt to type into the session. */
    public ReviewFeedback prepareFeedback(long reviewId) {
        captureLock.lock();
        try {
            var review = find(reviewId).orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));
            var images = images(reviewId);
            var marksByImageId = new HashMap<Long, List<ReviewMark>>();
            images.forEach(image -> marksByImageId.put(image.id(), marks(image.id())));
            var feedback = feedbackWriter.write(review, images, Map.copyOf(marksByImageId), directoryOf(reviewId));
            settle(reviewId, ReviewStatus.SENT);
            return feedback;
        } finally {
            captureLock.unlock();
        }
    }

    public void approve(long reviewId) {
        captureLock.lock();
        try {
            settle(reviewId, ReviewStatus.APPROVED);
        } finally {
            captureLock.unlock();
        }
    }

    public void delete(long reviewId) {
        captureLock.lock();
        try {
            if (repository.delete(reviewId)) {
                try {
                    deleteFolder(directoryOf(reviewId));
                } finally {
                    listeners.fire();
                }
            }
        } finally {
            captureLock.unlock();
        }
    }

    /** @return how many reviews were removed */
    public int deleteOlderThan(Instant cutoff) {
        var ids = repository.idsOlderThan(cutoff);
        try {
            transaction.executeWithoutResult(status -> ids.forEach(repository::delete));
            ids.forEach(id -> deleteFolder(directoryOf(id)));
        } finally {
            if (!ids.isEmpty()) {
                listeners.fire();
            }
        }
        return ids.size();
    }

    public Set<Long> knownIds() {
        return repository.knownIds();
    }

    /** @return unsubscribe handle; callback fires (any thread) after any change made through this service */
    public Runnable subscribe(Runnable listener) {
        return listeners.add(listener);
    }

    private void settle(long reviewId, ReviewStatus status) {
        repository.settle(reviewId, status);
        listeners.fire();
    }

    private void removeQuietly(long id) {
        repository.delete(id);
        deleteFolder(directoryOf(id));
    }

    private static void deleteFolder(Path folder) {
        try {
            if (Files.exists(folder)) {
                TreeDeleter.delete(folder);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
