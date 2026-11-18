package com.github.krpkbhdn.astrolabe.voice.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MetalProbeTest {

    @Test
    void offersTheMetalGpuOnMacOnly() {
        assertThat(probe(OperatingSystem.MACOS).probe()).containsExactly(ComputeDevice.metal());
        assertThat(probe(OperatingSystem.LINUX).probe()).isEmpty();
        assertThat(probe(OperatingSystem.WINDOWS).probe()).isEmpty();
    }

    private static MetalProbe probe(OperatingSystem os) {
        return new MetalProbe(new FakeHostEnvironment(os, Path.of("home")));
    }
}
