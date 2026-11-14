package com.github.krpkbhdn.ccmanager.codemode;

import java.util.Optional;

/** Time one script spent waiting for the user to answer a tool question; it is added to the script's timeout. */
final class ApprovalWait {

    private long total;
    private int open;
    private long openSince;
    private String openTool;

    synchronized void begin(String tool) {
        if (open++ == 0) {
            openSince = System.nanoTime();
            openTool = tool;
        }
    }

    synchronized void end() {
        if (--open == 0) {
            total += System.nanoTime() - openSince;
        }
    }

    /** The {@code server.tool} whose question is open now. */
    synchronized Optional<String> openTool() {
        return open > 0 ? Optional.of(openTool) : Optional.empty();
    }

    /** Finished waits plus the one still open. */
    synchronized long nanos() {
        return total + (open > 0 ? System.nanoTime() - openSince : 0);
    }
}
