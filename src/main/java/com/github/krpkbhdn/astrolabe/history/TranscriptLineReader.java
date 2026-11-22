package com.github.krpkbhdn.astrolabe.history;

import com.github.krpkbhdn.astrolabe.history.TranscriptIndexRepository.IndexedFile;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads a transcript file in batches of whole lines and tells whether it was replaced since it was last indexed. */
final class TranscriptLineReader {

    static final long MAX_LINE_BYTES = 16L << 20;
    private static final Logger log = LoggerFactory.getLogger(TranscriptLineReader.class);
    private static final int SKIP_CHUNK_BYTES = 64 * 1024;

    record Line(String text, long byteLength) {}

    private final long batchBytes;
    private final int batchLines;

    TranscriptLineReader(long batchBytes, int batchLines) {
        this.batchBytes = batchBytes;
        this.batchLines = batchLines;
    }

    /**
     * A file that shrank, whose last indexed byte is no line end any more, or that is fully indexed yet has a new
     * modification time (replaced by a same-size file), was replaced and must be read from the start.
     */
    boolean rewritten(Path file, IndexedFile known, long size) {
        long indexedBytes = known.indexedBytes();
        if (size < indexedBytes) {
            return true;
        }
        if (indexedBytes == 0) {
            return false;
        }
        if (indexedBytes == size) {
            return known.modifiedAt() != modifiedMillis(file);
        }
        try (var reader = new RandomAccessFile(file.toFile(), "r")) {
            reader.seek(indexedBytes - 1);
            return reader.read() != '\n';
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Up to a batch of whole lines from {@code from}; a longer first line widens the read until it fits, and an
     * unfinished last line is left out.
     */
    List<Line> nextLines(Path file, long from, long size) {
        try (var reader = new RandomAccessFile(file.toFile(), "r")) {
            long length = Math.min(size - from, batchBytes);
            while (true) {
                var bytes = new byte[(int) length];
                reader.seek(from);
                reader.readFully(bytes);
                var lines = wholeLines(bytes, batchLines);
                if (!lines.isEmpty() || from + length >= size) {
                    return lines;
                }
                if (length >= MAX_LINE_BYTES) {
                    return skipOversizedLine(file, reader, from, size);
                }
                length = Math.min(size - from, Math.min(length * 2, MAX_LINE_BYTES));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Steps over a line too long to index as an empty line of its byte length, or reads nothing while it is unfinished. */
    private static List<Line> skipOversizedLine(Path file, RandomAccessFile reader, long from, long size) throws IOException {
        var chunk = new byte[SKIP_CHUNK_BYTES];
        long position = from;
        reader.seek(position);
        while (position < size) {
            int read = reader.read(chunk, 0, (int) Math.min(chunk.length, size - position));
            if (read < 0) {
                break;
            }
            for (int i = 0; i < read; i++) {
                if (chunk[i] == '\n') {
                    long lineBytes = position + i + 1 - from;
                    log.warn("Skipped an oversized transcript line of {} bytes in {}", lineBytes, file.getFileName());
                    return List.of(new Line("", lineBytes));
                }
            }
            position += read;
        }
        return List.of();
    }

    static List<Line> wholeLines(byte[] bytes, int maxLines) {
        var lines = new ArrayList<Line>();
        int start = 0;
        for (int i = 0; i < bytes.length && lines.size() < maxLines; i++) {
            if (bytes[i] == '\n') {
                lines.add(new Line(new String(bytes, start, i - start, StandardCharsets.UTF_8), i + 1 - start));
                start = i + 1;
            }
        }
        return lines;
    }

    static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long modifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
