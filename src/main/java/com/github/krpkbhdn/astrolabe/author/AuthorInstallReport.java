package com.github.krpkbhdn.astrolabe.author;

import java.util.List;

/** Result of installing the chosen items (requirements included). */
public record AuthorInstallReport(List<ItemOutcome> outcomes) {

    public AuthorInstallReport {
        outcomes = List.copyOf(outcomes);
    }
}
