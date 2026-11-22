package com.github.krpkbhdn.astrolabe.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Mark clamping on the way in and tolerant point parsing on the way out of the database. */
final class ReviewMarkCodec {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewMarkCodec.class);
    private static final int MIN_FREEFORM_POINTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReviewMarkCodec() {
    }

    /** Clamps to the image box; a PIN has no size and a FREEFORM's box is recomputed from its points. */
    static MarkDraft normalised(MarkDraft mark) {
        return switch (mark.kind()) {
            case PIN -> new MarkDraft(mark.kind(), unit(mark.x()), unit(mark.y()), 0.0, 0.0, mark.comment());
            case FREEFORM -> freeform(mark);
            default -> new MarkDraft(mark.kind(), unit(mark.x()), unit(mark.y()), unit(mark.w()), unit(mark.h()), mark.comment());
        };
    }

    private static MarkDraft freeform(MarkDraft mark) {
        if (mark.points().size() < MIN_FREEFORM_POINTS) {
            throw new IllegalArgumentException("a freeform mark needs at least " + MIN_FREEFORM_POINTS + " points");
        }
        var points = mark.points().stream().map(p -> new MarkPoint(unit(p.x()), unit(p.y()))).toList();
        var minX = points.stream().mapToDouble(MarkPoint::x).min().orElseThrow();
        var minY = points.stream().mapToDouble(MarkPoint::y).min().orElseThrow();
        var maxX = points.stream().mapToDouble(MarkPoint::x).max().orElseThrow();
        var maxY = points.stream().mapToDouble(MarkPoint::y).max().orElseThrow();
        return new MarkDraft(mark.kind(), minX, minY, maxX - minX, maxY - minY, mark.comment(), points);
    }

    static String pointsJson(List<MarkPoint> points) {
        return JSON.writeValueAsString(points.stream().map(p -> new double[] {p.x(), p.y()}).toList());
    }

    private record ParsedPoints(List<MarkPoint> points, boolean repaired) {
    }

    /** Never throws: unusable pairs are dropped and out-of-range values clamped, so one bad row cannot hide the marks of an image. */
    private static ParsedPoints parsePoints(String json) {
        var points = new ArrayList<MarkPoint>();
        var repaired = false;
        try {
            var root = JSON.readTree(json == null ? "[]" : json);
            if (!root.isArray()) {
                return new ParsedPoints(List.of(), true);
            }
            for (var pair : root) {
                if (pair.isArray() && pair.size() >= 2 && pair.get(0).isNumber() && pair.get(1).isNumber()
                        && Double.isFinite(pair.get(0).doubleValue()) && Double.isFinite(pair.get(1).doubleValue())) {
                    var point = new MarkPoint(unit(pair.get(0).doubleValue()), unit(pair.get(1).doubleValue()));
                    repaired |= point.x() != pair.get(0).doubleValue() || point.y() != pair.get(1).doubleValue();
                    points.add(point);
                } else {
                    repaired = true;
                }
            }
        } catch (RuntimeException e) {
            return new ParsedPoints(List.of(), true);
        }
        return new ParsedPoints(List.copyOf(points), repaired);
    }

    private static List<MarkPoint> boundingBoxPolygon(double x, double y, double w, double h) {
        var x1 = unit(x + w);
        var y1 = unit(y + h);
        var x0 = unit(x);
        var y0 = unit(y);
        return List.of(new MarkPoint(x0, y0), new MarkPoint(x1, y0), new MarkPoint(x1, y1), new MarkPoint(x0, y1));
    }

    private static double unit(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    static ReviewMark mapMark(ResultSet rs, int row) throws SQLException {
        var kind = MarkKind.valueOf(rs.getString("kind"));
        var x = rs.getDouble("x");
        var y = rs.getDouble("y");
        var w = rs.getDouble("w");
        var h = rs.getDouble("h");
        var parsed = parsePoints(rs.getString("points"));
        var points = parsed.points();
        var repaired = parsed.repaired();
        if (kind == MarkKind.FREEFORM && points.size() < MIN_FREEFORM_POINTS) {
            points = boundingBoxPolygon(x, y, w, h);
            repaired = true;
        }
        if (repaired) {
            LOG.warn("A stored review mark had unusable points and was repaired.");
        }
        return new ReviewMark(rs.getLong("id"), rs.getLong("image_id"), kind, x, y, w, h, rs.getString("comment"), points);
    }
}
