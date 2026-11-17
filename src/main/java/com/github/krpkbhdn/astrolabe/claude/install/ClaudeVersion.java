package com.github.krpkbhdn.astrolabe.claude.install;

import java.util.regex.Pattern;

/** Compares Claude Code versions of the form {@code major.minor.patch}; an unknown or unparsable version is never at least anything. */
public final class ClaudeVersion {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private ClaudeVersion() {
    }

    public static boolean atLeast(String version, String minimum) {
        var actual = parts(version);
        var required = parts(minimum);
        if (actual == null || required == null) {
            return false;
        }
        for (var i = 0; i < required.length; i++) {
            if (actual[i] != required[i]) {
                return actual[i] > required[i];
            }
        }
        return true;
    }

    private static int[] parts(String version) {
        var matcher = VERSION.matcher(version == null ? "" : version.strip());
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
