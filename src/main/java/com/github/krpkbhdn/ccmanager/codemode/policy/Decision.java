package com.github.krpkbhdn.ccmanager.codemode.policy;

/** The outcome of a tool call policy check; reason is shown to the script as the error text when denied. */
public record Decision(Kind kind, String reason) {

    public enum Kind { ALLOWED_AUTO, ALLOWED_USER, ALLOWED_PROJECT_SETTING, DENIED }

    public static final Decision AUTO = new Decision(Kind.ALLOWED_AUTO, "");
    public static final Decision USER = new Decision(Kind.ALLOWED_USER, "");
    /** Allowed only because the project setting allows every tool call, so the audit tells it from a read-only or granted call. */
    public static final Decision PROJECT_SETTING = new Decision(Kind.ALLOWED_PROJECT_SETTING, "");

    public static Decision denied(String reason) {
        return new Decision(Kind.DENIED, reason);
    }

    public boolean allowed() {
        return kind != Kind.DENIED;
    }
}
