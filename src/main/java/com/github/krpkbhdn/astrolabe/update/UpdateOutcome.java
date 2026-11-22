package com.github.krpkbhdn.astrolabe.update;

/** What the previous update run reported through its result file. */
public record UpdateOutcome(Status status, String step) {

    public enum Status { OK, AUTH_CANCELLED, FAILED }
}
