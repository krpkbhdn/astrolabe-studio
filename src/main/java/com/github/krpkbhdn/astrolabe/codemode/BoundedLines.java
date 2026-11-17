package com.github.krpkbhdn.astrolabe.codemode;

import java.io.BufferedReader;
import java.io.IOException;

/** Reads one line at a time with a length cap, so a peer that never sends a line break cannot fill the heap. */
public final class BoundedLines {

    /** The line passed the cap; the stream is left in the middle of it and must be dropped. */
    public static final class TooLongException extends RuntimeException {
        public TooLongException() {
            super(null, null, false, false);
        }
    }

    private BoundedLines() {}

    /** @return the next line without its terminator, or null at the end of the stream; throws {@link TooLongException} past {@code maxChars} */
    public static String read(BufferedReader in, int maxChars) throws IOException {
        var line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (c != '\r') {
                line.append((char) c);
            }
            if (line.length() > maxChars) {
                throw new TooLongException();
            }
        }
        return line.isEmpty() ? null : line.toString();
    }
}
