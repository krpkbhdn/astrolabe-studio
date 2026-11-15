package com.github.krpkbhdn.ccmanager.toolchain.managed;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads a "sha256sum" style file; anything unclear is an error, never a pass. */
public final class ChecksumFile {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ChecksumFile() {}

    public static void verify(String checksumFileText, String actualSha256) throws IOException {
        String expected = expected(checksumFileText);
        if (!expected.equals(actualSha256.toLowerCase(Locale.ROOT))) {
            throw new IOException("SHA-256 mismatch: the download does not match its checksum file");
        }
    }

    static String expected(String text) throws IOException {
        String trimmed = text.replace("﻿", "").strip();
        String token = trimmed.split("\\s+", 2)[0];
        if (!SHA_256.matcher(token).matches()) {
            throw new IOException("Checksum file is missing or has no valid SHA-256");
        }
        return token.toLowerCase(Locale.ROOT);
    }
}
