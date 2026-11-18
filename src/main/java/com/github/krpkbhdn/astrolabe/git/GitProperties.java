package com.github.krpkbhdn.astrolabe.git;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Git tab tunables: command timeouts, change-watcher debounce and size, diff and untracked-file caps. */
@ConfigurationProperties("astrolabe.git")
public record GitProperties(
        @DefaultValue("60s") Duration commandTimeout,
        @DefaultValue("10m") Duration syncTimeout,
        @DefaultValue("300ms") Duration watchDebounce,
        @DefaultValue("2000") int maxWatchedDirectories,
        @DefaultValue("2000") int maxDiffLines,
        @DefaultValue("1048576") long maxUntrackedBytes) {

    /** The values of an empty configuration, for code that runs outside Spring. */
    public static GitProperties defaults() {
        return new Binder(new MapConfigurationPropertySource()).bindOrCreate("astrolabe.git", GitProperties.class);
    }

    public GitProperties {
        positive("astrolabe.git.command-timeout", commandTimeout);
        positive("astrolabe.git.sync-timeout", syncTimeout);
        nonNegative("astrolabe.git.watch-debounce", watchDebounce);
        positive("astrolabe.git.max-watched-directories", maxWatchedDirectories);
        positive("astrolabe.git.max-diff-lines", maxDiffLines);
        positive("astrolabe.git.max-untracked-bytes", maxUntrackedBytes);
    }
}
