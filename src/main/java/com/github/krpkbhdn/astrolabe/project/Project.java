package com.github.krpkbhdn.astrolabe.project;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A registered directory, or a group (a service directory holding member projects); groupId is null when ungrouped,
 * trustOverride is null when the project follows the app default trust level.
 */
public record Project(long id, String name, Path path, Instant createdAt, ModelSelection modelSelection,
                      boolean group, Long groupId, TrustLevel trustOverride) {

    public Project(long id, String name, Path path, Instant createdAt, ModelSelection modelSelection, boolean group, Long groupId) {
        this(id, name, path, createdAt, modelSelection, group, groupId, null);
    }

    public Project(long id, String name, Path path, Instant createdAt, ModelSelection modelSelection) {
        this(id, name, path, createdAt, modelSelection, false, null);
    }

    public Project(long id, String name, Path path, Instant createdAt) {
        this(id, name, path, createdAt, ModelSelection.DEFAULT);
    }
}
