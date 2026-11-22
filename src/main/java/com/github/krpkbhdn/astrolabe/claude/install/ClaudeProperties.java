package com.github.krpkbhdn.astrolabe.claude.install;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** User override of the claude binary: an absolute file path checked before any lookup; blank = detect. */
@ConfigurationProperties("astrolabe.claude")
public record ClaudeProperties(@DefaultValue("") String path) {
}
