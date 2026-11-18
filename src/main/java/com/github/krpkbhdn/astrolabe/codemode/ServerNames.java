package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.review.PromptText;
import java.util.List;

/** Server names shown to Claude (instructions, errors): sanitised, each shortened and the list capped. */
public final class ServerNames {

    static final int MAX_LISTED = 20;
    static final int MAX_LENGTH = 40;

    private ServerNames() {
    }

    public static String listed(List<String> names) {
        var shown = names.stream().limit(MAX_LISTED).map(ServerNames::shown).toList();
        var more = names.size() - shown.size();
        return String.join(", ", shown) + (more > 0 ? " and " + more + " more" : "");
    }

    public static String shown(String name) {
        return shown(name, MAX_LENGTH);
    }

    public static String shown(String name, int maxLength) {
        var clean = PromptText.oneLine(name);
        return clean.length() > maxLength ? clean.substring(0, maxLength) + "..." : clean;
    }
}
