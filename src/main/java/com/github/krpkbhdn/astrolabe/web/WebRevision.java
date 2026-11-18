package com.github.krpkbhdn.astrolabe.web;

import com.github.krpkbhdn.astrolabe.review.ReviewStatus;
import java.time.Instant;

/** A numbered batch of items sent to an agent together; {@code sentAt} and {@code settledAt} are null until then. */
public record WebRevision(long id, long projectId, int number, String astrolabeSessionId, String note, ReviewStatus status, Instant createdAt,
        Instant sentAt, Instant settledAt) {
}
