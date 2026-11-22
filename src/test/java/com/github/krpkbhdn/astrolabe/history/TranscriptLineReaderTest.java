package com.github.krpkbhdn.astrolabe.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.history.TranscriptIndexRepository.IndexedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptLineReaderTest {

    @TempDir
    Path dir;

    private final TranscriptLineReader reader = new TranscriptLineReader(1024, 10);

    @Test
    void readsWholeLinesAndLeavesTheUnfinishedOneOut() throws IOException {
        var file = write("one\ntwo\npart");

        var lines = reader.nextLines(file, 0, Files.size(file));

        assertThat(lines).extracting(TranscriptLineReader.Line::text).containsExactly("one", "two");
        assertThat(lines).extracting(TranscriptLineReader.Line::byteLength).containsExactly(4L, 4L);
    }

    @Test
    void skipsOversizedLineAsEmptyLineOfItsByteLength() throws IOException {
        var oversized = "x".repeat((int) TranscriptLineReader.MAX_LINE_BYTES + 1) + "\n";
        var file = write(oversized + "next\n");

        var lines = reader.nextLines(file, 0, Files.size(file));

        assertThat(lines).containsExactly(new TranscriptLineReader.Line("", oversized.length()));
    }

    @Test
    void treatsFileSmallerThanIndexedOffsetAsRewritten() throws IOException {
        var file = write("short\n");
        var known = new IndexedFile(1, 100, "", Files.getLastModifiedTime(file).toMillis());

        assertThat(reader.rewritten(file, known, Files.size(file))).isTrue();
    }

    @Test
    void treatsFileContinuingAfterLineEndAsNotRewritten() throws IOException {
        var file = write("one\ntwo\n");
        var known = new IndexedFile(1, 4, "", 0);

        assertThat(reader.rewritten(file, known, Files.size(file))).isFalse();
    }

    private Path write(String content) throws IOException {
        return Files.writeString(dir.resolve("t.jsonl"), content, StandardCharsets.UTF_8);
    }
}
