package com.github.krpkbhdn.astrolabe.toolchain;

public enum ArchiveKind {
    ZIP(".zip"), TAR_GZ(".tar.gz");

    private final String suffix;

    ArchiveKind(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }
}
