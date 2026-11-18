package com.github.krpkbhdn.astrolabe.project;

/** How much Claude Code may do in a project's sessions without asking; applies to sessions started after the change. */
public enum TrustLevel {
    ASK, TRUSTED, AUTONOMOUS;

    /** An unknown stored value is the safe default. */
    public static TrustLevel stored(String value) {
        try {
            return value == null ? ASK : valueOf(value);
        } catch (IllegalArgumentException e) {
            return ASK;
        }
    }

    /** A stored per-project override: null means "use the app default". */
    static TrustLevel storedOverride(String value) {
        return value == null ? null : stored(value);
    }

    /** What a project's sessions start with: groups always ask, otherwise the project's override or else the app default. */
    public static TrustLevel effective(Project project, TrustLevel appDefault) {
        if (project.group()) {
            return ASK;
        }
        return project.trustOverride() != null ? project.trustOverride() : appDefault;
    }
}
