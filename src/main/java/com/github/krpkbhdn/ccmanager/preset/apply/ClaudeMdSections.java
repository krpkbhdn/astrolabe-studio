package com.github.krpkbhdn.ccmanager.preset.apply;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure planning of the managed blocks in a CLAUDE.md: a block is replaced only between its own markers, text outside them is never touched.
 * Marker text inside a fenced code block is not a marker, and a block whose markers are unclosed or doubled is a conflict, never rewritten.
 */
public final class ClaudeMdSections {

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final Pattern ANY_BLOCK = Pattern.compile("<!-- cc-manager:begin (\\S+) -->.*?<!-- cc-manager:end \\1 -->", Pattern.DOTALL);
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

    private ClaudeMdSections() {}

    /** @param text the whole new file, null unless the action is CREATE or UPDATE */
    record Plan(ChangeAction action, String text, String detail) {}

    static String slug(String title) {
        var slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "section" : slug;
    }

    static String block(String title, String body) {
        var slug = slug(title);
        return "<!-- cc-manager:begin " + slug + " -->\n" + heading(title, body) + "\n<!-- cc-manager:end " + slug + " -->";
    }

    static Plan plan(String file, String title, String body) {
        return plan(CLAUDE_MD, file, title, body);
    }

    static Plan plan(String fileName, String file, String title, String body) {
        var wanted = heading(title, body);
        if (wanted.contains("cc-manager:begin") || wanted.contains("cc-manager:end")) {
            return new Plan(ChangeAction.CONFLICT, null, "The instruction contains block markers and is not written");
        }
        var visible = withoutFences(file);
        var slug = slug(title);
        var begin = Pattern.compile("<!-- cc-manager:begin " + Pattern.quote(slug) + " -->").matcher(visible);
        var end = Pattern.compile("<!-- cc-manager:end " + Pattern.quote(slug) + " -->").matcher(visible);
        var begins = count(begin);
        var ends = count(end);
        if (begins > 0 || ends > 0) {
            return begins == 1 && ends == 1 && begin.find(0) && end.find(0) && begin.end() <= end.start()
                    ? existing(fileName, file, begin.end(), end.start(), wanted)
                    : new Plan(ChangeAction.CONFLICT, null, "The markers of this section in " + fileName + " are unclosed or repeated, fix them by hand");
        }
        var eol = ProjectFiles.lineEnding(file);
        if (Pattern.compile("(?m)^## " + Pattern.quote(title) + "[ \\t]*\\r?$").matcher(ANY_BLOCK.matcher(visible).replaceAll("")).find()) {
            return new Plan(ChangeAction.CONFLICT, null, "'## " + title + "' is already written by hand in " + fileName);
        }
        var text = new StringBuilder(file);
        if (!file.isEmpty()) {
            text.append(file.endsWith("\n") ? "" : eol).append(eol);
        }
        text.append(block(title, body).replace("\n", eol)).append(eol);
        return new Plan(ChangeAction.CREATE, text.toString(), "Add a section to " + fileName);
    }

    private static Plan existing(String fileName, String file, int innerStart, int innerEnd, String wanted) {
        if (ProjectFiles.lf(file.substring(innerStart, innerEnd)).strip().equals(wanted)) {
            return new Plan(ChangeAction.PRESENT, null, "Already in " + fileName);
        }
        var eol = ProjectFiles.lineEnding(file);
        var inner = eol + wanted.replace("\n", eol) + eol;
        return new Plan(ChangeAction.UPDATE, file.substring(0, innerStart) + inner + file.substring(innerEnd), "Replace the managed section in " + fileName);
    }

    /** @return the file without the managed block of {@code title}, or unchanged when that block is missing, unclosed or repeated */
    public static String remove(String file, String title) {
        var visible = withoutFences(file);
        var slug = Pattern.quote(slug(title));
        var begin = Pattern.compile("<!-- cc-manager:begin " + slug + " -->").matcher(visible);
        var end = Pattern.compile("<!-- cc-manager:end " + slug + " -->").matcher(visible);
        if (count(begin) != 1 || count(end) != 1 || !begin.find(0) || !end.find(0) || begin.start() > end.start()) {
            return file;
        }
        var before = file.substring(0, begin.start());
        var after = file.substring(end.end()).replaceFirst("^[ \t]*\r?\n", "");
        if (after.isBlank()) {
            return before.isBlank() ? "" : before.stripTrailing() + ProjectFiles.lineEnding(file);
        }
        if (before.isBlank()) {
            return after.stripLeading();
        }
        return before.matches("(?s).*\n[ \t]*\r?\n") ? before + after.stripLeading() : before + after;
    }

    private static int count(Matcher matcher) {
        var count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** The file with every fenced code block blanked out, same length so offsets still match. */
    static String withoutFences(String file) {
        var out = new StringBuilder(file.length());
        String fence = null;
        for (var line : file.split("(?<=\n)", -1)) {
            var match = FENCE.matcher(line);
            var opens = fence == null && match.find();
            var closes = fence != null && match.find() && match.group(1).charAt(0) == fence.charAt(0) && match.group(1).length() >= fence.length()
                    && line.substring(match.end()).isBlank();
            var inside = fence != null || opens;
            out.append(inside ? line.replaceAll("[^\r\n]", " ") : line);
            fence = opens ? match.group(1) : closes ? null : fence;
        }
        return out.toString();
    }

    private static String heading(String title, String body) {
        return "## " + title + "\n" + ProjectFiles.lf(body).strip();
    }
}
