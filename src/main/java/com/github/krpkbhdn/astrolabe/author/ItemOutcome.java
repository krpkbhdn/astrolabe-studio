package com.github.krpkbhdn.astrolabe.author;

import com.github.krpkbhdn.astrolabe.author.install.InstallOutcome;

public record ItemOutcome(String itemId, InstallOutcome outcome) {

    public boolean failed() {
        return outcome.hasFailures();
    }
}
