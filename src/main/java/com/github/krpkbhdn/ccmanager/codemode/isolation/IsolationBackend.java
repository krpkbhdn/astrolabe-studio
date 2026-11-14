package com.github.krpkbhdn.ccmanager.codemode.isolation;

/** Where Code Mode scripts run: in-process GraalJS or a Docker/Podman container. */
public enum IsolationBackend {
    GRAALJS, DOCKER, PODMAN
}
