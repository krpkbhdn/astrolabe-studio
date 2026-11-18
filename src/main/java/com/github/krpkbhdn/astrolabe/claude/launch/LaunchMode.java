package com.github.krpkbhdn.astrolabe.claude.launch;

public enum LaunchMode {
    /** claude */
    NEW,
    /** claude --continue */
    CONTINUE,
    /** claude --resume (the CLI's own session picker) */
    RESUME_PICKER
}
