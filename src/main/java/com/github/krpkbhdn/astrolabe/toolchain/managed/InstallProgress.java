package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.util.function.Consumer;

/** Rendered progress text: finished lines plus one percentage line that is replaced in place. */
final class InstallProgress {

    private static final double MEGABYTE = 1024.0 * 1024.0;

    private final Consumer<String> sink;
    private final StringBuilder lines = new StringBuilder();
    private String percentLine = "";

    InstallProgress(Consumer<String> sink) {
        this.sink = sink;
    }

    void line(String text) {
        lines.append(text).append('\n');
        emit();
    }

    void download(int percent, long read, long total) {
        percentLine = total > 0
                ? "Downloading " + percent + "% (" + megabytes(read) + " of " + megabytes(total) + " MB)"
                : "Downloading " + megabytes(read) + " MB";
        emit();
    }

    void endDownload() {
        if (!percentLine.isEmpty()) {
            lines.append(percentLine).append('\n');
            percentLine = "";
            emit();
        }
    }

    private void emit() {
        sink.accept(lines + percentLine);
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / MEGABYTE);
    }
}
