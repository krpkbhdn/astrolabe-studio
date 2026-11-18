package com.github.krpkbhdn.astrolabe.review;

public record ReviewImage(long id, long reviewId, int position, String label, String fileName, int width, int height) {
}
