package com.github.krpkbhdn.astrolabe.workspace;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The login-shell PATH probe (Unix): whether it runs and how long one shell may take. */
@ConfigurationProperties("astrolabe.login-shell")
public record LoginShellProperties(@DefaultValue("true") boolean enabled, @DefaultValue("5") int timeoutSeconds) {

    public LoginShellProperties {
        positive("astrolabe.login-shell.timeout-seconds", timeoutSeconds);
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }
}
