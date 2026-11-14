package com.github.krpkbhdn.ccmanager.codemode;

import static com.github.krpkbhdn.ccmanager.config.PropertyRules.positive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of Code Mode (cc-manager.code-mode); the image default is a mutable tag, use image@sha256:... to pin it.
 * Three nested limits: {@code toolCallTimeout} (one remote tool call) < {@code timeout} (one script, the user's answer time not counted)
 * < {@code maxScriptTime} (hard cap the sandboxes enforce); sized for browser automation (first npx launch, navigation, screenshots).
 */
@ConfigurationProperties("cc-manager.code-mode")
public record CodeModeProperties(@DefaultValue("10m") Duration timeout, @DefaultValue("100000") long statementLimit,
        @DefaultValue("20000") int maxOutputChars, @DefaultValue("10") int searchLimit,
        @DefaultValue("10m") Duration idleClientClose, @DefaultValue("node:22-alpine") String image,
        @DefaultValue("256m") String containerMemory, @DefaultValue("1") double containerCpus,
        @DefaultValue("30d") Duration auditRetention,
        @DefaultValue("3m") Duration toolCallTimeout,
        @DefaultValue("1h") Duration maxScriptTime, @DefaultValue("30s") Duration environmentCheckTtl,
        @DefaultValue("50") int maxImagesPerSession, @DefaultValue("104857600") long maxImageBytesPerSession,
        @DefaultValue("5s") Duration candidateRecheckTtl, @DefaultValue("2s") Duration idleCancelDelay) {

    @ConstructorBinding
    public CodeModeProperties {
        positive("cc-manager.code-mode.timeout", timeout);
        positive("cc-manager.code-mode.statement-limit", statementLimit);
        positive("cc-manager.code-mode.max-output-chars", maxOutputChars);
        positive("cc-manager.code-mode.search-limit", searchLimit);
        positive("cc-manager.code-mode.idle-client-close", idleClientClose);
        positive("cc-manager.code-mode.audit-retention", auditRetention);
        positive("cc-manager.code-mode.tool-call-timeout", toolCallTimeout);
        positive("cc-manager.code-mode.max-script-time", maxScriptTime);
        positive("cc-manager.code-mode.environment-check-ttl", environmentCheckTtl);
        positive("cc-manager.code-mode.max-images-per-session", maxImagesPerSession);
        positive("cc-manager.code-mode.max-image-bytes-per-session", maxImageBytesPerSession);
        positive("cc-manager.code-mode.candidate-recheck-ttl", candidateRecheckTtl);
        positive("cc-manager.code-mode.idle-cancel-delay", idleCancelDelay);
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("cc-manager.code-mode.image must not be blank");
        }
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** A copy with some limits replaced; every other value is taken from the original. */
    public static final class Builder {

        private final CodeModeProperties base;
        private Duration timeout;
        private Duration toolCallTimeout;
        private Duration candidateRecheckTtl;
        private Duration idleCancelDelay;

        private Builder(CodeModeProperties base) {
            this.base = base;
            this.timeout = base.timeout;
            this.toolCallTimeout = base.toolCallTimeout;
            this.candidateRecheckTtl = base.candidateRecheckTtl;
            this.idleCancelDelay = base.idleCancelDelay;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder toolCallTimeout(Duration value) {
            this.toolCallTimeout = value;
            return this;
        }

        public Builder candidateRecheckTtl(Duration value) {
            this.candidateRecheckTtl = value;
            return this;
        }

        public Builder idleCancelDelay(Duration value) {
            this.idleCancelDelay = value;
            return this;
        }

        public CodeModeProperties build() {
            return new CodeModeProperties(timeout, base.statementLimit, base.maxOutputChars, base.searchLimit, base.idleClientClose, base.image,
                    base.containerMemory, base.containerCpus, base.auditRetention, toolCallTimeout, base.maxScriptTime, base.environmentCheckTtl,
                    base.maxImagesPerSession, base.maxImageBytesPerSession, candidateRecheckTtl, idleCancelDelay);
        }
    }
}
