package com.github.krpkbhdn.astrolabe.git;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings of the claude one-shot that describes changes and of the git reads feeding it. */
@ConfigurationProperties("astrolabe.git.description")
public record ChangeDescriptionProperties(
        @DefaultValue("haiku") String model,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("30000") int contextChars,
        @DefaultValue("4000") int untrackedFileChars) {

    public ChangeDescriptionProperties {
        positive("astrolabe.git.description.timeout", timeout);
        positive("astrolabe.git.description.context-chars", contextChars);
        positive("astrolabe.git.description.untracked-file-chars", untrackedFileChars);
    }
}
