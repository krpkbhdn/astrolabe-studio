package com.github.krpkbhdn.astrolabe.voice.recognize;

/** Recognised speech; {@code text} is empty when nothing was said. */
public record Transcript(String text, String language) {}
