package com.github.krpkbhdn.astrolabe.toolchain;

/** One part of setting up language servers for a project: plan() gives PENDING or MANUAL steps, run() their final outcome. */
public record LspSetupStep(Kind kind, String toolId, Outcome outcome, String message) {

    public enum Kind { PREREQUISITE, SERVER, PLUGIN }

    /** MANUAL: nothing the app can run on this system, message says what to do by hand. */
    public enum Outcome { PENDING, DONE, FAILED, SKIPPED, MANUAL }

    LspSetupStep with(Outcome newOutcome, String newMessage) {
        return new LspSetupStep(kind, toolId, newOutcome, newMessage);
    }
}
