package com.github.krpkbhdn.astrolabe.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** All SQL of the visual review and the row mapping; callers own the transactions. */
class ReviewRepository {

    /** The number of images of a review and its highest position, -1 when it has none. */
    record ImageRow(int count, int lastPosition) {
    }

    private static final String SELECT = "SELECT id, project_id, astrolabe_session_id, title, note, status, created_at, settled_at FROM review";
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Review> list(long projectId) {
        return jdbc.sql(SELECT + " WHERE project_id = ? ORDER BY id DESC").param(projectId).query(ReviewRepository::mapReview).list();
    }

    Optional<Review> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(ReviewRepository::mapReview).optional();
    }

    List<ReviewImage> images(long reviewId) {
        return jdbc.sql("SELECT id, review_id, position, label, file_name, width, height FROM review_image WHERE review_id = ? ORDER BY position")
                .param(reviewId).query(ReviewRepository::mapImage).list();
    }

    List<ReviewMark> marks(long imageId) {
        return jdbc.sql("SELECT id, image_id, kind, x, y, w, h, comment, points FROM review_mark WHERE image_id = ? ORDER BY id")
                .param(imageId).query(ReviewMarkCodec::mapMark).list();
    }

    List<ReviewService.CaptureTarget> captureTargets(long projectId, int maxImages) {
        return jdbc.sql("SELECT r.id, r.title, r.created_at, COUNT(i.id) AS images FROM review r LEFT JOIN review_image i ON i.review_id = r.id "
                        + "WHERE r.project_id = ? AND r.status = 'PENDING' GROUP BY r.id ORDER BY r.id DESC")
                .param(projectId).query((rs, row) -> new ReviewService.CaptureTarget(rs.getLong("id"), rs.getString("title"),
                        instant(rs.getString("created_at")), rs.getInt("images"), maxImages)).list();
    }

    int countForProject(long projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM review WHERE project_id = ?").param(projectId).query(Integer.class).single();
    }

    long insertReview(long projectId, String astrolabeSessionId, String title, String note) {
        return jdbc.sql("INSERT INTO review (project_id, astrolabe_session_id, title, note) VALUES (?, ?, ?, ?) RETURNING id")
                .param(projectId).param(astrolabeSessionId).param(title).param(note).query(Long.class).single();
    }

    void insertImages(long reviewId, List<StoredImage> stored) {
        for (var position = 0; position < stored.size(); position++) {
            insertImage(reviewId, position, stored.get(position));
        }
    }

    void insertImage(long reviewId, int position, StoredImage image) {
        jdbc.sql("INSERT INTO review_image (review_id, position, label, file_name, width, height) VALUES (?, ?, ?, ?, ?, ?)")
                .param(reviewId).param(position).param(image.label()).param(image.fileName()).param(image.width()).param(image.height()).update();
    }

    ImageRow imageRow(long reviewId) {
        return jdbc.sql("SELECT COUNT(*), COALESCE(MAX(position), -1) FROM review_image WHERE review_id = ?").param(reviewId)
                .query((rs, n) -> new ImageRow(rs.getInt(1), rs.getInt(2))).single();
    }

    /** @throws IllegalArgumentException when the image no longer exists */
    void replaceMarks(long imageId, List<MarkDraft> marks) {
        if (jdbc.sql("SELECT COUNT(*) FROM review_image WHERE id = ?").param(imageId).query(Integer.class).single() == 0) {
            throw new IllegalArgumentException("the review was deleted");
        }
        jdbc.sql("DELETE FROM review_mark WHERE image_id = ?").param(imageId).update();
        for (var mark : marks) {
            jdbc.sql("INSERT INTO review_mark (image_id, kind, x, y, w, h, comment, points) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .param(imageId).param(mark.kind().name()).param(mark.x()).param(mark.y())
                    .param(mark.w()).param(mark.h()).param(mark.comment()).param(ReviewMarkCodec.pointsJson(mark.points())).update();
        }
    }

    void settle(long reviewId, ReviewStatus status) {
        jdbc.sql("UPDATE review SET status = ?, settled_at = CURRENT_TIMESTAMP WHERE id = ?").param(status.name()).param(reviewId).update();
    }

    boolean delete(long reviewId) {
        return jdbc.sql("DELETE FROM review WHERE id = ?").param(reviewId).update() > 0;
    }

    List<Long> idsOlderThan(Instant cutoff) {
        return jdbc.sql("SELECT id FROM review WHERE created_at < ?").param(SQLITE_TIMESTAMP.format(cutoff)).query(Long.class).list();
    }

    Set<Long> knownIds() {
        return new HashSet<>(jdbc.sql("SELECT id FROM review").query(Long.class).list());
    }

    private static Review mapReview(ResultSet rs, int row) throws SQLException {
        return new Review(rs.getLong("id"), rs.getLong("project_id"), rs.getString("astrolabe_session_id"), rs.getString("title"), rs.getString("note"),
                ReviewStatus.valueOf(rs.getString("status")), instant(rs.getString("created_at")), instant(rs.getString("settled_at")));
    }

    private static ReviewImage mapImage(ResultSet rs, int row) throws SQLException {
        return new ReviewImage(rs.getLong("id"), rs.getLong("review_id"), rs.getInt("position"), rs.getString("label"), rs.getString("file_name"),
                rs.getInt("width"), rs.getInt("height"));
    }

    private static Instant instant(String sqliteTimestamp) {
        return sqliteTimestamp == null ? null : LocalDateTime.parse(sqliteTimestamp, SQLITE_TIMESTAMP).toInstant(ZoneOffset.UTC);
    }
}
