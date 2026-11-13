package com.github.krpkbhdn.ccmanager.claude.hook;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The 8.3 name of an existing Windows path (no spaces), so one command string needs no quoting in any shell. */
final class WindowsShortPath {

    private static final Logger log = LoggerFactory.getLogger(WindowsShortPath.class);
    private static final int MAX_CHARS = 32_768;
    private static final int BYTES_PER_CHAR = 2;

    private WindowsShortPath() {
    }

    /** The short form, or the path itself when none exists (8.3 names disabled, missing file) or the call fails. */
    static String of(String path) {
        try (var arena = Arena.ofConfined()) {
            var getShortPathName = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("kernel32", arena).find("GetShortPathNameW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            var out = arena.allocate((long) MAX_CHARS * BYTES_PER_CHAR);
            var chars = (int) getShortPathName.invokeExact(arena.allocateFrom(path, StandardCharsets.UTF_16LE), out, MAX_CHARS);
            return chars == 0 || chars > MAX_CHARS ? path : new String(out.asSlice(0, (long) chars * BYTES_PER_CHAR).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
        } catch (Throwable e) {
            log.warn("GetShortPathNameW failed, the status line command keeps the long path: {}", e.toString());
            return path;
        }
    }
}
