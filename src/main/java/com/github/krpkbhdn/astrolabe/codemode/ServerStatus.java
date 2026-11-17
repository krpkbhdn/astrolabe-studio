package com.github.krpkbhdn.astrolabe.codemode;

import java.util.Optional;

/** A Code Mode server of a project as one session sees it: script alias, saved name, scope (user, project, local, plugin or unknown); problem is the reason it is unusable (ineligible or failed to connect). */
public record ServerStatus(String name, String stored, String scope, String transport, boolean connected, Optional<String> problem) {
}
