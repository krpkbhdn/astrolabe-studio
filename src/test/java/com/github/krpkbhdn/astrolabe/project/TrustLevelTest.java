package com.github.krpkbhdn.astrolabe.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrustLevelTest {

    private static Project project(boolean group, TrustLevel override) {
        return new Project(1, "p", Path.of("p"), Instant.now(), ModelSelection.DEFAULT, group, null, override);
    }

    @Test
    void overrideBeatsTheAppDefault() {
        assertThat(TrustLevel.effective(project(false, TrustLevel.ASK), TrustLevel.AUTONOMOUS)).isEqualTo(TrustLevel.ASK);
    }

    @Test
    void noOverrideFollowsTheAppDefault() {
        assertThat(TrustLevel.effective(project(false, null), TrustLevel.TRUSTED)).isEqualTo(TrustLevel.TRUSTED);
    }

    @Test
    void groupAlwaysAsks() {
        assertThat(TrustLevel.effective(project(true, null), TrustLevel.AUTONOMOUS)).isEqualTo(TrustLevel.ASK);
        assertThat(TrustLevel.effective(project(true, TrustLevel.AUTONOMOUS), TrustLevel.AUTONOMOUS)).isEqualTo(TrustLevel.ASK);
    }
}
