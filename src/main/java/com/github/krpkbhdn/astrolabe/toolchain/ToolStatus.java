package com.github.krpkbhdn.astrolabe.toolchain;

import java.util.Objects;

/** Detection result for one tool. */
public record ToolStatus(String id, ToolState state, String version, String detail) {

    public ToolStatus {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
        version = version == null ? "" : version;
        detail = detail == null ? "" : detail;
    }
}
