package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.toolchain.managed.ManagedToolsDirectory;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.nio.file.Path;

/** Builds an {@link ExecutableLookup} over a fake host and a throwaway managed tools folder. */
final class ExecutableLookups {

    static final Path DATA_DIR = Path.of("build", "tools-test").toAbsolutePath();

    private ExecutableLookups() {
    }

    static ExecutableLookup lookup(HostEnvironment host) {
        return new ExecutableLookup(host, new ManagedToolsDirectory(new AppProperties(DATA_DIR)));
    }
}
