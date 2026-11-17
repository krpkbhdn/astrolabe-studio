package com.github.krpkbhdn.astrolabe.review;

import java.nio.file.Path;

public record ReviewFeedback(Path markdown, String prompt) {
}
