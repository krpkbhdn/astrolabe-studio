package com.github.krpkbhdn.ccmanager.toolchain;

/** Whether a language's Claude LSP plugin is effectively on for a project; NOT_NEEDED is a language with no plugin. */
public enum LspPluginState {
    ENABLED, DISABLED, NOT_INSTALLED, NOT_NEEDED;

    /** A language whose plugin is off is not checked at all: its server is not probed. */
    public boolean serverMatters() {
        return this == ENABLED || this == NOT_NEEDED;
    }
}
