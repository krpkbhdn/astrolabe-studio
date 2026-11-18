package com.github.krpkbhdn.astrolabe.voice.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Unpacks the whisper.cpp runtime libraries of a tar.gz flat into a directory. Only regular files whose plain
 * name is a runtime library are written, so links, executables and any path in an entry name are never used.
 */
final class TarGzExtraction {

    private static final Pattern RUNTIME_LIBRARY = Pattern.compile(
            "libggml-base\\.so\\.[\\d.]+|libggml\\.so\\.[\\d.]+|libwhisper\\.so\\.[\\d.]+|libggml-cpu[\\w.-]*\\.so");

    private TarGzExtraction() {}

    /** Replaces an existing {@code directory}; an archive without a library or a failed one leaves it untouched. */
    static void extract(Path archive, Path directory, long maxBytes) throws IOException {
        ZipExtraction.stageAndReplace(directory, partial -> {
            try (var in = Files.newInputStream(archive); var tar = new TarArchiveInputStream(new GZIPInputStream(in))) {
                var remaining = maxBytes;
                var written = 0;
                for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                    var name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    if (entry.isDirectory() || entry.isSymbolicLink() || entry.isLink() || !RUNTIME_LIBRARY.matcher(name).matches()) {
                        continue;
                    }
                    try (var out = Files.newOutputStream(partial.resolve(name))) {
                        remaining -= ZipExtraction.copyAtMost(tar, out, remaining);
                    }
                    written++;
                }
                if (written == 0) {
                    throw new IOException("Archive holds no whisper runtime library");
                }
            }
        });
    }
}
