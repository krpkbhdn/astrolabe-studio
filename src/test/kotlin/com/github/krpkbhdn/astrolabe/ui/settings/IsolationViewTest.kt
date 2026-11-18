package com.github.krpkbhdn.astrolabe.ui.settings

import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IsolationViewTest {
    private val ready = RuntimeStatus(true, "", true)
    private val noImage = RuntimeStatus(true, "", false)
    private val down = RuntimeStatus(false, "Docker is installed but its daemon is not running", null)

    @Test
    fun detectionShowsReasonOnlyWhenUnavailable() {
        assertThat(IsolationView.detection(null)).isEqualTo("Checking...")
        assertThat(IsolationView.detection(ready)).isEqualTo("Installed and running")
        assertThat(IsolationView.detection(down)).contains("daemon is not running")
    }

    @Test
    fun imageLineIsHiddenWhileRuntimeUnusable() {
        assertThat(IsolationView.image(down, "node")).isNull()
        assertThat(IsolationView.image(ready, "node")).contains("present")
        assertThat(IsolationView.image(noImage, "node")).contains("never pulled automatically")
    }

    @Test
    fun warningOnlyForUnusableContainerBackend() {
        assertThat(IsolationView.warning(IsolationBackend.GRAALJS, null)).isNull()
        assertThat(IsolationView.warning(IsolationBackend.DOCKER, ready)).isNull()
        assertThat(IsolationView.warning(IsolationBackend.DOCKER, down)).contains("no silent fallback")
        assertThat(IsolationView.warning(IsolationBackend.PODMAN, noImage)).contains("no silent fallback")
    }

    @Test
    fun pullNeedsRunningRuntimeMissingImageAndNoPullInFlight() {
        assertThat(IsolationView.canPull(noImage, false)).isTrue()
        assertThat(IsolationView.canPull(noImage, true)).isFalse()
        assertThat(IsolationView.canPull(ready, false)).isFalse()
        assertThat(IsolationView.canPull(down, false)).isFalse()
    }
}
