package com.github.krpkbhdn.astrolabe.review;

import java.time.Instant;

public record Review(long id, long projectId, String astrolabeSessionId, String title, String note, ReviewStatus status, Instant createdAt, Instant settledAt) {
}
