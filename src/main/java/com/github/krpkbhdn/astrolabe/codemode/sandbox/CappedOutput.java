package com.github.krpkbhdn.astrolabe.codemode.sandbox;

/**
 * Keeps the head and a ring of the tail of what a script printed and counts the dropped middle, so a flood of log lines
 * never holds more than the output limit in memory. Shared by both sandboxes; thread-safe because a timed-out run is abandoned
 * while its worker may still print.
 */
final class CappedOutput {

    private static final String MARKER = "\n[truncated %d chars]\n";

    private final int headLimit;
    private final int tailLimit;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder tail = new StringBuilder();
    private long dropped;
    private boolean hasLines;

    CappedOutput(int maxChars) {
        this.headLimit = maxChars / 2;
        this.tailLimit = maxChars - headLimit;
    }

    synchronized void append(String text) {
        int split = Math.min(Math.max(0, headLimit - head.length()), text.length());
        head.append(text, 0, split);
        int tailStart = Math.max(split, text.length() - tailLimit);
        dropped += tailStart - split;
        tail.append(text, tailStart, text.length());
        if (tail.length() > tailLimit) {
            int excess = tail.length() - tailLimit;
            dropped += excess;
            tail.delete(0, excess);
        }
    }

    /** Lines are joined by a newline with none at the end; an empty first line is kept. */
    synchronized void appendLine(String line) {
        append(hasLines ? "\n" + line : line);
        hasLines = true;
    }

    synchronized boolean truncated() {
        return dropped > 0;
    }

    synchronized String render() {
        return dropped == 0 ? head.toString() + tail : head + MARKER.formatted(dropped) + tail;
    }
}
