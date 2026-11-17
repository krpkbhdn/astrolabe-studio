package com.github.krpkbhdn.astrolabe.extension;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Timeouts of the claude plugin/mcp commands: local queries, commands that download, the MCP health check and the wait for a browser sign-in, plus how many bytes of a SKILL.md are read for its frontmatter. */
@ConfigurationProperties("astrolabe.extension")
public record ExtensionProperties(
        @DefaultValue("30") int queryTimeoutSeconds,
        @DefaultValue("180") int networkTimeoutSeconds,
        @DefaultValue("90") int mcpHealthTimeoutSeconds,
        @DefaultValue("300") int mcpLoginTimeoutSeconds,
        @DefaultValue("16384") int skillFrontmatterLimitBytes) {

    public Duration queryTimeout() {
        return Duration.ofSeconds(queryTimeoutSeconds);
    }

    public Duration networkTimeout() {
        return Duration.ofSeconds(networkTimeoutSeconds);
    }

    public Duration mcpHealthTimeout() {
        return Duration.ofSeconds(mcpHealthTimeoutSeconds);
    }

    public Duration mcpLoginTimeout() {
        return Duration.ofSeconds(mcpLoginTimeoutSeconds);
    }

    public ExtensionProperties {
        positive("astrolabe.extension.query-timeout-seconds", queryTimeoutSeconds);
        positive("astrolabe.extension.network-timeout-seconds", networkTimeoutSeconds);
        positive("astrolabe.extension.mcp-health-timeout-seconds", mcpHealthTimeoutSeconds);
        positive("astrolabe.extension.mcp-login-timeout-seconds", mcpLoginTimeoutSeconds);
        positive("astrolabe.extension.skill-frontmatter-limit-bytes", skillFrontmatterLimitBytes);
    }
}
