package com.github.krpkbhdn.astrolabe.ui.terminal;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Terminal tunables: how many scrolled-off lines each terminal keeps. */
@ConfigurationProperties("astrolabe.terminal")
public record TerminalProperties(@DefaultValue("5000") int scrollbackLines) {

    public static final int DEFAULT_SCROLLBACK_LINES = 5000;

    public TerminalProperties {
        positive("astrolabe.terminal.scrollback-lines", scrollbackLines);
    }
}
